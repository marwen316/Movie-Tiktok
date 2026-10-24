package com.codenova.moviefactory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

@Service
public class ProductionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    @Value("${OPENAI_API_KEY:}") private String apiKey;
    @Value("${MEDIA_DIR:/data/media}") private String mediaDir;
    @Value("${OPENAI_TEXT_MODEL:gpt-4.1-mini}") private String textModel;
    @Value("${OPENAI_IMAGE_MODEL:gpt-image-2.5-flare}") private String imageModel;

    public ProductionService(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }

    public UUID create(String title, Integer year, String synopsis) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO movies(id,title,release_year,synopsis) VALUES(?,?,?,?)",id,title,year,synopsis);
        return id;
    }
    public Map<String,Object> movie(UUID id) {
        try { return db.queryForMap("SELECT * FROM movies WHERE id=?",id); }
        catch (EmptyResultDataAccessException e) { throw new NoSuchElementException("Movie not found: "+id); }
    }
    public List<Map<String,Object>> episodes(UUID movieId) {
        return db.queryForList("SELECT * FROM episodes WHERE movie_id=? ORDER BY episode_number",movieId);
    }
    public UUID start(UUID movieId) {
        if(apiKey.isBlank()) throw new IllegalStateException("OPENAI_API_KEY is not configured");
        Map<String,Object> movie=movie(movieId);
        UUID episode=UUID.randomUUID();
        db.update("INSERT INTO episodes(id,movie_id,status) VALUES(?,?,'QUEUED')",episode,movieId);
        executor.submit(()->produce(episode,movie));
        return episode;
    }
    public Map<String,Object> episode(UUID id) {
        try { return db.queryForMap("SELECT * FROM episodes WHERE id=?",id); }
        catch (EmptyResultDataAccessException e) { throw new NoSuchElementException("Episode not found: "+id); }
    }
    private JsonNode post(String endpoint, Object payload) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/"+endpoint))
          .timeout(Duration.ofMinutes(4)).header("Authorization","Bearer "+apiKey)
          .header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()/100!=2) throw new IOException("OpenAI "+endpoint+" HTTP "+response.statusCode()+": "+response.body().substring(0,Math.min(350,response.body().length())));
        return json.readTree(response.body());
    }
    private byte[] speech(String narration) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/audio/speech"))
          .timeout(Duration.ofMinutes(4)).header("Authorization","Bearer "+apiKey)
          .header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model","gpt-4o-mini-tts","voice","marin","input",narration,"response_format","mp3")))).build();
        HttpResponse<byte[]> response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()/100!=2) throw new IOException("Speech generation failed: HTTP "+response.statusCode());
        return response.body();
    }
    private void produce(UUID episode, Map<String,Object> movie) {
        try {
            db.update("UPDATE episodes SET status='GENERATING' WHERE id=?",episode);
            String prompt="Write an original French voice-over of 110-130 words summarizing ONLY the following supplied facts about the movie. Do not invent scenes or quote dialogue. Return plain narration, no markdown. Title: "+movie.get("title")+"; Synopsis: "+movie.get("synopsis");
            JsonNode completion=post("chat/completions",Map.of("model",textModel,"messages",List.of(Map.of("role","user","content",prompt))));
            String narration=completion.path("choices").path(0).path("message").path("content").asText().trim();
            if(narration.isBlank() || narration.length()>4096) throw new IllegalStateException("Invalid narration");
            db.update("UPDATE episodes SET narration=?,status='GENERATING_AUDIO' WHERE id=?",narration,episode);
            Path dir=Path.of(mediaDir,episode.toString()); Files.createDirectories(dir);
            Files.write(dir.resolve("voice.mp3"),speech(narration));
            db.update("UPDATE episodes SET status='GENERATING_IMAGES' WHERE id=?",episode);
            for(int i=0;i<6;i++) {
                String imagePrompt="Original cinematic vertical illustration, no text, no logo, no copyrighted character likeness. Scene "+(i+1)+" of 6 inspired by this factual synopsis (do not recreate film frames): "+movie.get("synopsis");
                JsonNode image=post("images/generations",Map.of("model",imageModel,"prompt",imagePrompt,"size","1024x1536","quality","low","n",1));
                String base64=image.path("data").path(0).path("b64_json").asText();
                if(base64.isBlank()) throw new IOException("Image response contains no image");
                Path file=dir.resolve(String.format("scene_%02d.png",i+1));
                Files.write(file,Base64.getDecoder().decode(base64));
                db.update("INSERT INTO assets(id,episode_id,file_path,origin,license_evidence,rights_verified) VALUES(?,?,?,'AI_GENERATED',?,false)",
                    UUID.randomUUID(),episode,file.toString(),"Generated with "+imageModel+"; pending human review");
            }
            db.update("UPDATE episodes SET status='RENDERING' WHERE id=?",episode);
            // Duration follows narration; each image occupies one sixth of the actual audio duration.
            double duration=probeDuration(dir.resolve("voice.mp3"));
            if(duration<=0 || duration>180) throw new IllegalStateException("Unexpected audio duration: "+duration);
            List<String> command=new ArrayList<>(List.of("ffmpeg","-y"));
            for(int i=1;i<=6;i++) command.addAll(List.of("-loop","1","-t",String.format(Locale.ROOT,"%.3f",duration/6.0),"-i",dir.resolve(String.format("scene_%02d.png",i)).toString()));
            command.addAll(List.of("-i",dir.resolve("voice.mp3").toString()));
            StringBuilder filter=new StringBuilder();
            for(int i=0;i<6;i++) filter.append("[").append(i).append(":v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,fps=30,setsar=1[v").append(i).append("];");
            for(int i=0;i<6;i++) filter.append("[v").append(i).append("]");
            filter.append("concat=n=6:v=1:a=0[outv]");
            Path video=dir.resolve("output.mp4");
            command.addAll(List.of("-filter_complex",filter.toString(),"-map","[outv]","-map","6:a","-c:v","libx264","-preset","veryfast","-crf","25","-pix_fmt","yuv420p","-c:a","aac","-b:a","160k","-shortest","-movflags","+faststart",video.toString()));
            run(command,Duration.ofMinutes(8));
            if(!Files.isRegularFile(video) || Files.size(video)<1000) throw new IOException("Video not generated");
            db.update("UPDATE episodes SET status='READY_FOR_REVIEW',video_path=? WHERE id=?",video.toString(),episode);
        } catch(Exception e) {
            db.update("UPDATE episodes SET status='FAILED',error_message=? WHERE id=?",String.valueOf(e.getMessage()).substring(0,Math.min(1000,String.valueOf(e.getMessage()).length())),episode);
        }
    }
    private double probeDuration(Path audio) throws Exception {
        Process p=new ProcessBuilder("ffprobe","-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",audio.toString()).redirectErrorStream(true).start();
        String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        if(!p.waitFor(30,TimeUnit.SECONDS)||p.exitValue()!=0) throw new IOException("ffprobe failed: "+output);
        return Double.parseDouble(output);
    }
    private void run(List<String> cmd, Duration timeout) throws Exception {
        Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        CompletableFuture<String> output=CompletableFuture.supplyAsync(()->{try{return new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){return e.toString();}});
        if(!p.waitFor(timeout.toSeconds(),TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("FFmpeg timeout");}
        String log=output.get(10,TimeUnit.SECONDS);
        if(p.exitValue()!=0) throw new IOException("FFmpeg failed: "+log.substring(Math.max(0,log.length()-1200)));
    }
    public void approve(UUID id) {
        Integer pending=db.queryForObject("SELECT count(*) FROM assets WHERE episode_id=? AND rights_verified=false",Integer.class,id);
        if(pending!=null && pending>0) throw new IllegalStateException("Verify all assets before approval");
        if(!"READY_FOR_REVIEW".equals(episode(id).get("status"))) throw new IllegalStateException("Episode not ready");
        db.update("UPDATE episodes SET status='APPROVED' WHERE id=?",id);
    }
    public void verifyAsset(UUID assetId,String evidence) {
        if(evidence==null || evidence.isBlank()) throw new IllegalArgumentException("Evidence required");
        if(db.update("UPDATE assets SET rights_verified=true,license_evidence=? WHERE id=?",evidence,assetId)==0) throw new NoSuchElementException("Asset not found");
    }
    public List<Map<String,Object>> assets(UUID episodeId) {
        return db.queryForList("SELECT * FROM assets WHERE episode_id=?",episodeId);
    }
}
