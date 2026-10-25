package com.codenova.moviefactory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/tiktok/schedule")
public class TikTokScheduleController {
    private final JdbcTemplate db;
    private final TikTokPublishController publisher;
    @Value("${TIKTOK_ADMIN_KEY:}") private String adminKey;

    public TikTokScheduleController(JdbcTemplate db,TikTokPublishController publisher){
        this.db=db;this.publisher=publisher;
    }
    @PostConstruct
    public void init(){
        db.execute("CREATE TABLE IF NOT EXISTS tiktok_scheduled_posts ("+
            "id UUID PRIMARY KEY,episode_id UUID NOT NULL UNIQUE REFERENCES episodes(id),"+
            "caption TEXT NOT NULL,scheduled_at TIMESTAMPTZ NOT NULL,status VARCHAR(32) NOT NULL,"+
            "publish_id TEXT,error_message TEXT,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
    }
    private void auth(String supplied){
        if(adminKey==null||adminKey.length()<32||supplied==null||
            !MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cle administrateur incorrecte");
    }
    public record ScheduleRequest(UUID episodeId,String caption,OffsetDateTime scheduledAt){}
    @PostMapping
    public Map<String,Object> schedule(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key,
                                        @RequestBody ScheduleRequest request){
        auth(key);
        if(request==null||request.episodeId()==null||request.scheduledAt()==null||request.caption()==null||
            request.caption().isBlank()||request.caption().length()>2200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Episode, legende et date requis");
        if(!request.scheduledAt().toInstant().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date de publication dans le passe");
        List<Map<String,Object>> episodes=db.queryForList("SELECT status FROM episodes WHERE id=?",request.episodeId());
        if(episodes.isEmpty()||!"APPROVED".equals(episodes.get(0).get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Episode non approuve");
        UUID id=UUID.randomUUID();
        try{
            db.update("INSERT INTO tiktok_scheduled_posts(id,episode_id,caption,scheduled_at,status) VALUES(?,?,?,?,'QUEUED')",
                id,request.episodeId(),request.caption().trim(),Timestamp.from(request.scheduledAt().toInstant()));
        }catch(org.springframework.dao.DuplicateKeyException e){
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Cet episode a deja une programmation");
        }
        return Map.of("id",id,"status","QUEUED");
    }
    @GetMapping
    public List<Map<String,Object>> list(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key){
        auth(key);
        return db.queryForList("SELECT id,episode_id,caption,scheduled_at,status,publish_id,error_message FROM tiktok_scheduled_posts ORDER BY scheduled_at DESC LIMIT 100");
    }
    @PostMapping("/{id}/cancel")
    public Map<String,Object> cancel(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key,@PathVariable UUID id){
        auth(key);
        int changed=db.update("UPDATE tiktok_scheduled_posts SET status='CANCELLED',updated_at=NOW() WHERE id=? AND status='QUEUED'",id);
        if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Publication non annulable");
        return Map.of("status","CANCELLED");
    }
    // Called by n8n every minute. Atomic claim prevents two workers from posting the same row.
    @PostMapping("/run-due")
    public Map<String,Object> runDue(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key){
        auth(key);
        List<Map<String,Object>> candidates=db.queryForList(
            "SELECT id,episode_id,caption FROM tiktok_scheduled_posts WHERE status='QUEUED' AND scheduled_at<=NOW() ORDER BY scheduled_at LIMIT 5");
        int claimed=0;
        for(Map<String,Object> item:candidates){
            UUID id=(UUID)item.get("id");
            if(db.update("UPDATE tiktok_scheduled_posts SET status='SENDING',updated_at=NOW() WHERE id=? AND status='QUEUED'",id)==0)continue;
            claimed++;
            try{
                Map<String,Object> result=publisher.publishApproved((UUID)item.get("episode_id"),(String)item.get("caption"));
                db.update("UPDATE tiktok_scheduled_posts SET status='PROCESSING',publish_id=?,updated_at=NOW() WHERE id=?",
                    result.get("publishId"),id);
            }catch(Exception e){
                // No automatic retry: the remote upload may have succeeded before a network failure.
                db.update("UPDATE tiktok_scheduled_posts SET status='NEEDS_REVIEW',error_message=?,updated_at=NOW() WHERE id=?",
                    String.valueOf(e.getMessage()).substring(0,Math.min(900,String.valueOf(e.getMessage()).length())),id);
            }
        }
        return Map.of("claimed",claimed);
    }
    @PostMapping("/check-status")
    public Map<String,Object> checkStatus(@RequestHeader(value="X-TikTok-Admin-Key",required=false) String key){
        auth(key);
        List<Map<String,Object>> posts=db.queryForList(
            "SELECT id,publish_id FROM tiktok_scheduled_posts WHERE status='PROCESSING' AND publish_id IS NOT NULL ORDER BY updated_at LIMIT 10");
        int checked=0;
        for(Map<String,Object> post:posts){
            try{
                JsonNode result=publisher.fetchPublicationStatus((String)post.get("publish_id"));
                String status=result.path("status").asText();
                if("PUBLISH_COMPLETE".equals(status)){
                    db.update("UPDATE tiktok_scheduled_posts SET status='PUBLISHED',updated_at=NOW() WHERE id=?",post.get("id"));
                }else if("FAILED".equals(status)){
                    db.update("UPDATE tiktok_scheduled_posts SET status='NEEDS_REVIEW',error_message=?,updated_at=NOW() WHERE id=?",
                        result.path("fail_reason").asText("TikTok publication failed"),post.get("id"));
                }
                checked++;
            }catch(Exception ignored){
                // Keep PROCESSING; next scheduled status check can safely retry the read-only query.
            }
        }
        return Map.of("checked",checked);
    }
}
