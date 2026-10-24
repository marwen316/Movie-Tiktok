package com.codenova.moviefactory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/tiktok")
public class TikTokPublishController {
    private static final Logger log=LoggerFactory.getLogger(TikTokPublishController.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    @Value("${TIKTOK_CLIENT_KEY:}") private String clientKey;
    @Value("${TIKTOK_CLIENT_SECRET:}") private String clientSecret;
    @Value("${TIKTOK_TOKEN_ENCRYPTION_KEY:}") private String encryptionKey;
    @Value("${TIKTOK_ADMIN_KEY:}") private String adminKey;

    public TikTokPublishController(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}

    // The temporary Cloudflare tunnel is public: require a separate operator secret for every publish operation.
    private void authorize(String supplied){
        if(adminKey==null||adminKey.length()<32||supplied==null||
          !MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cle administrateur TikTok absente ou incorrecte");
    }

    private String decrypt(String encoded) throws Exception{
        byte[] key=MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        byte[] bytes=Base64.getDecoder().decode(encoded);
        byte[] nonce=Arrays.copyOfRange(bytes,0,12);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
    }
    private String encrypt(String value) throws Exception{
        byte[] key=MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        byte[] nonce=new byte[12];new java.security.SecureRandom().nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] result=new byte[12+encrypted.length];
        System.arraycopy(nonce,0,result,0,12);System.arraycopy(encrypted,0,result,12,encrypted.length);
        return Base64.getEncoder().encodeToString(result);
    }
    private String token() throws Exception{
        List<Map<String,Object>> accounts=db.queryForList(
            "SELECT open_id,access_token_enc,refresh_token_enc,access_expires_at,refresh_expires_at FROM tiktok_oauth_accounts ORDER BY connected_at DESC LIMIT 1");
        if(accounts.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Connecte d'abord ton compte TikTok");
        Map<String,Object> a=accounts.get(0);
        long now=System.currentTimeMillis()/1000;
        if(((Number)a.get("access_expires_at")).longValue()>now+120)return decrypt((String)a.get("access_token_enc"));
        if(((Number)a.get("refresh_expires_at")).longValue()<=now+120)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Session TikTok expiree : reconnecte ton compte");
        String body="client_key="+enc(clientKey)+"&client_secret="+enc(clientSecret)+
            "&grant_type=refresh_token&refresh_token="+enc(decrypt((String)a.get("refresh_token_enc")));
        HttpRequest req=HttpRequest.newBuilder(URI.create("https://open.tiktokapis.com/v2/oauth/token/"))
            .header("Content-Type","application/x-www-form-urlencoded").timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(res.statusCode()!=200){log.warn("TikTok token refresh failed HTTP {}",res.statusCode());throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Echec du renouvellement TikTok HTTP "+res.statusCode());}
        JsonNode data=json.readTree(res.body());
        if(data.path("access_token").asText().isBlank()||data.path("refresh_token").asText().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Renouvellement TikTok refuse");
        db.update("UPDATE tiktok_oauth_accounts SET access_token_enc=?,refresh_token_enc=?,access_expires_at=?,refresh_expires_at=? WHERE open_id=?",
            encrypt(data.path("access_token").asText()),encrypt(data.path("refresh_token").asText()),
            now+data.path("expires_in").asLong(),now+data.path("refresh_expires_in").asLong(),a.get("open_id"));
        return data.path("access_token").asText();
    }
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private JsonNode post(String endpoint,String access,Object payload) throws Exception{
        HttpRequest req=HttpRequest.newBuilder(URI.create("https://open.tiktokapis.com"+endpoint))
            .header("Authorization","Bearer "+access).header("Content-Type","application/json; charset=UTF-8")
            .timeout(Duration.ofSeconds(40)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        HttpResponse<String> res=http.send(req,HttpResponse.BodyHandlers.ofString());
        JsonNode body=json.readTree(res.body());
        if(res.statusCode()/100!=2||!"ok".equals(body.path("error").path("code").asText())) {
            String errorCode=body.path("error").path("code").asText("HTTP "+res.statusCode());
            String errorMessage=body.path("error").path("message").asText("");
            log.warn("TikTok API failure endpoint={} httpStatus={} code={} message={} logId={}",
                endpoint,res.statusCode(),errorCode,errorMessage,body.path("error").path("log_id").asText(""));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"TikTok "+endpoint+": "+errorCode+" - "+errorMessage);
        }
        return body.path("data");
    }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,Object>> handleTikTokError(ResponseStatusException e){
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of(
            "error",e.getReason()==null?"Erreur TikTok":e.getReason()));
    }
    @PostMapping("/creator-info")
    public JsonNode creatorInfo(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key) throws Exception{
        authorize(key);
        return post("/v2/post/publish/creator_info/query/",token(),Map.of());
    }
    public record PublishRequest(UUID episodeId,String caption,boolean consent) {}

    @PostMapping("/publish-private")
    public Map<String,Object> publish(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key,
                                      @RequestBody PublishRequest request) throws Exception{
        authorize(key);
        if(request==null||request.episodeId()==null||!request.consent())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirmation explicite requise");
        Map<String,Object> episode;
        try{episode=db.queryForMap("SELECT status,video_path FROM episodes WHERE id=?",request.episodeId());}
        catch(org.springframework.dao.EmptyResultDataAccessException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Episode introuvable");}
        if(!"APPROVED".equals(episode.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Verifie les droits des assets puis approuve l'episode avant publication");
        if(!(episode.get("video_path") instanceof String file))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Fichier MP4 introuvable");
        Path video=Path.of(file);
        if(!Files.isRegularFile(video))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"MP4 absent du volume Docker");
        long size=Files.size(video);
        if(size<1||size>512L*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Taille MP4 non prise en charge pour ce test (max 512 Mo)");
        String access=token();
        JsonNode creator=post("/v2/post/publish/creator_info/query/",access,Map.of());
        boolean privateAllowed=false;
        for(JsonNode option:creator.path("privacy_level_options"))if("SELF_ONLY".equals(option.asText()))privateAllowed=true;
        if(!privateAllowed)throw new ResponseStatusException(HttpStatus.CONFLICT,"TikTok n'autorise pas SELF_ONLY pour ce compte");
        String caption=Objects.toString(request.caption(),"").trim();
        if(caption.isEmpty()||caption.length()>2200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Legende requise (max 2200 caracteres)");
        // Chunking: 10 MiB chunks, remainder merged into last chunk.
        long chunk=10L*1024*1024;
        int count=(int)Math.max(1,size/chunk);
        if(size<=64L*1024*1024){chunk=size;count=1;}
        Map<String,Object> info=Map.of("source","FILE_UPLOAD","video_size",size,"chunk_size",chunk,"total_chunk_count",count);
        Map<String,Object> postInfo=new LinkedHashMap<>();
        postInfo.put("title",caption);postInfo.put("privacy_level","SELF_ONLY");
        postInfo.put("disable_duet",true);postInfo.put("disable_stitch",true);postInfo.put("disable_comment",true);
        postInfo.put("is_aigc",true);
        JsonNode initialized=post("/v2/post/publish/video/init/",access,Map.of("post_info",postInfo,"source_info",info));
        String publishId=initialized.path("publish_id").asText();
        String uploadUrl=initialized.path("upload_url").asText();
        if(publishId.isBlank()||uploadUrl.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"TikTok n'a pas retourne d'URL d'envoi");
        URI uri=URI.create(uploadUrl);
        if(!"https".equalsIgnoreCase(uri.getScheme())||
           !(uri.getHost().equals("open-upload.tiktokapis.com")||uri.getHost().endsWith(".tiktokapis.com")))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Domaine d'upload TikTok inattendu");
        try(java.io.InputStream in=Files.newInputStream(video)){
            for(int i=0;i<count;i++){
                long start=i*chunk;
                int length=Math.toIntExact(i==count-1?size-start:chunk);
                byte[] bytes=in.readNBytes(length);
                if(bytes.length!=length)throw new java.io.IOException("Lecture MP4 incomplete");
                HttpRequest upload=HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                    .header("Content-Type","video/mp4")
                    .header("Content-Range","bytes "+start+"-"+(start+length-1)+"/"+size)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                HttpResponse<String> response=http.send(upload,HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()/100!=2){
                    log.warn("TikTok upload failed HTTP {} publishId={} chunk={}",response.statusCode(),publishId,i);
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"TikTok upload HTTP "+response.statusCode()+" (publish_id="+publishId+")");
                }
            }
        }
        return Map.of("publishId",publishId,"status","PROCESSING","privacy","SELF_ONLY");
    }
    @PostMapping("/publication-status")
    public JsonNode publicationStatus(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key,
                                      @RequestBody Map<String,String> body) throws Exception{
        authorize(key);
        String publishId=body.getOrDefault("publishId","");
        if(publishId.isBlank()||publishId.length()>128)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"publishId invalide");
        return post("/v2/post/publish/status/fetch/",token(),Map.of("publish_id",publishId));
    }
}
