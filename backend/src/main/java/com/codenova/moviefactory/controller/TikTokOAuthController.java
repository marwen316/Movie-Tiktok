package com.codenova.moviefactory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.Base64;

@RestController
@RequestMapping("/api/tiktok")
public class TikTokOAuthController {
    private static final String STATE_COOKIE="movie_tiktok_oauth_state";
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final SecureRandom random=new SecureRandom();
    @Value("${TIKTOK_CLIENT_KEY:}") private String clientKey;
    @Value("${TIKTOK_CLIENT_SECRET:}") private String clientSecret;
    @Value("${TIKTOK_REDIRECT_URI:}") private String redirectUri;
    @Value("${TIKTOK_TOKEN_ENCRYPTION_KEY:}") private String encryptionKey;

    public TikTokOAuthController(JdbcTemplate db,ObjectMapper mapper){this.db=db;this.mapper=mapper;}

    @PostConstruct
    public void init(){
        db.execute("CREATE TABLE IF NOT EXISTS tiktok_oauth_accounts ("+
          "open_id VARCHAR(255) PRIMARY KEY, access_token_enc TEXT NOT NULL, refresh_token_enc TEXT NOT NULL, "+
          "access_expires_at BIGINT NOT NULL, refresh_expires_at BIGINT NOT NULL, connected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }

    @GetMapping("/status")
    public Map<String,Object> status(){
        Integer count=db.queryForObject("SELECT COUNT(*) FROM tiktok_oauth_accounts",Integer.class);
        return Map.of("connected",count!=null&&count>0,"configured",configured());
    }

    @GetMapping("/connect")
    public void connect(HttpServletResponse response) throws Exception{
        if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TikTok OAuth non configure");
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String state=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ResponseCookie cookie=ResponseCookie.from(STATE_COOKIE,state).httpOnly(true).secure(true)
            .sameSite("Lax").path("/api/tiktok").maxAge(Duration.ofMinutes(10)).build();
        response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());
        String url="https://www.tiktok.com/v2/auth/authorize/?client_key="+encode(clientKey)+
          "&scope="+encode("user.info.basic,video.publish")+
          "&response_type=code&redirect_uri="+encode(redirectUri)+"&state="+encode(state);
        response.sendRedirect(url);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required=false) String code,
                         @RequestParam(required=false) String state,
                         @RequestParam(required=false) String error,
                         @CookieValue(value=STATE_COOKIE,required=false) String expected,
                         HttpServletResponse response) throws Exception{
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(STATE_COOKIE,"").httpOnly(true)
            .secure(true).sameSite("Lax").path("/api/tiktok").maxAge(Duration.ZERO).build().toString());
        if(error!=null||code==null||state==null||expected==null||
           !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Connexion TikTok refusee ou state OAuth invalide");
        if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TikTok OAuth non configure");
        String body="client_key="+encode(clientKey)+"&client_secret="+encode(clientSecret)+
            "&code="+encode(code)+"&grant_type=authorization_code&redirect_uri="+encode(redirectUri);
        HttpRequest req=HttpRequest.newBuilder(URI.create("https://open.tiktokapis.com/v2/oauth/token/"))
            .header("Content-Type","application/x-www-form-urlencoded").timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> result=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(result.statusCode()!=200)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Echec OAuth TikTok HTTP "+result.statusCode());
        JsonNode data=mapper.readTree(result.body());
        String openId=data.path("open_id").asText();
        String access=data.path("access_token").asText();
        String refresh=data.path("refresh_token").asText();
        if(openId.isBlank()||access.isBlank()||refresh.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Reponse OAuth TikTok incomplete");
        long now=System.currentTimeMillis()/1000;
        db.update("INSERT INTO tiktok_oauth_accounts(open_id,access_token_enc,refresh_token_enc,access_expires_at,refresh_expires_at) "+
          "VALUES(?,?,?,?,?) ON CONFLICT(open_id) DO UPDATE SET access_token_enc=EXCLUDED.access_token_enc, "+
          "refresh_token_enc=EXCLUDED.refresh_token_enc,access_expires_at=EXCLUDED.access_expires_at, "+
          "refresh_expires_at=EXCLUDED.refresh_expires_at,connected_at=CURRENT_TIMESTAMP",
          openId,encrypt(access),encrypt(refresh),now+data.path("expires_in").asLong(),
          now+data.path("refresh_expires_in").asLong());
        response.sendRedirect("/");
    }

    private boolean configured(){
        return !clientKey.isBlank()&&!clientSecret.isBlank()&&!redirectUri.isBlank()&&!encryptionKey.isBlank();
    }
    private String encrypt(String value) throws Exception{
        byte[] key=MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all=new byte[nonce.length+encrypted.length];
        System.arraycopy(nonce,0,all,0,nonce.length);
        System.arraycopy(encrypted,0,all,nonce.length,encrypted.length);
        return Base64.getEncoder().encodeToString(all);
    }
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
}
