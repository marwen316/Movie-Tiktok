package com.codenova.moviefactory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/movie-lookup")
public class MovieLookupController {
    @Value("${TMDB_API_TOKEN:}") private String token;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public MovieLookupController(ObjectMapper json){this.json=json;}
    public record Film(String title,Integer releaseYear,String synopsis,String originalTitle,long tmdbId){}
    @GetMapping
    public List<Film> search(@RequestParam String title) {
        if(title==null||title.isBlank()||title.length()>180) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Titre invalide");
        if(token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configurer TMDB_API_TOKEN dans .env");
        try{
            String query=URLEncoder.encode(title.trim(),StandardCharsets.UTF_8);
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.themoviedb.org/3/search/movie?language=fr-FR&include_adult=false&query="+query))
                .header("Authorization","Bearer "+token).header("Accept","application/json").timeout(Duration.ofSeconds(25)).GET().build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"TMDB HTTP "+response.statusCode());
            JsonNode results=json.readTree(response.body()).path("results");
            List<Film> films=new ArrayList<>();
            for(JsonNode item:results){
                String overview=item.path("overview").asText("").trim();
                if(overview.isBlank()) continue;
                String date=item.path("release_date").asText("");
                Integer year=date.matches("\\d{4}-.*")?Integer.parseInt(date.substring(0,4)):null;
                films.add(new Film(item.path("title").asText(),year,overview,item.path("original_title").asText(),item.path("id").asLong()));
                if(films.size()==8)break;
            }
            return films;
        }catch(ResponseStatusException e){throw e;}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Recherche TMDB indisponible",e);}
    }
}
