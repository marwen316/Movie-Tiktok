import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

interface FilmResult { title:string; releaseYear:number|null; synopsis:string; originalTitle:string; tmdbId:number; }
interface Movie { id:string; title:string; }
interface Episode { id:string; movie_id:string; episode_number:number; status:string; narration?:string|null; error_message?:string|null; }

@Component({selector:'app-root',standalone:true,imports:[CommonModule,FormsModule],templateUrl:'./app.component.html',styleUrl:'./app.component.css'})
export class AppComponent implements OnInit,OnDestroy {
  title=''; year:number|null=null; synopsis=''; movieId='';
  searchResults:FilmResult[]=[]; searching=false;
  movie:Movie|null=null; episodes:Episode[]=[]; selected:Episode|null=null;
  busy=false; message=''; error='';
  private timer:ReturnType<typeof setInterval>|null=null;
  readonly labels:Record<string,string>={QUEUED:'En attente',GENERATING:'Narration',GENERATING_AUDIO:'Voix française',GENERATING_IMAGES:'Illustrations',RENDERING:'Montage vidéo',READY_FOR_REVIEW:'Prêt à visionner',APPROVED:'Approuvé',FAILED:'Échec'};
  constructor(private readonly http:HttpClient){}
  ngOnInit():void { const saved=localStorage.getItem('movie-factory-last-movie'); if(saved){this.movieId=saved;void this.loadMovie();} }
  ngOnDestroy():void {this.stopPolling();}
  async lookup():Promise<void>{
    if(!this.title.trim())return;
    this.searching=true;this.error='';this.searchResults=[];
    try{
      this.searchResults=await firstValueFrom(this.http.get<FilmResult[]>('/api/movie-lookup',{params:{title:this.title.trim()}}));
      if(!this.searchResults.length)this.error='Aucun film avec un synopsis disponible. Essaie un titre plus précis.';
    }catch(e){this.showError(e);}finally{this.searching=false;}
  }
  async chooseFilm(film:FilmResult):Promise<void>{
    this.title=film.title;this.year=film.releaseYear;this.synopsis=film.synopsis;this.searchResults=[];
    await this.createMovie();
    if(this.movie)await this.generate();
  }
  async createMovie():Promise<void>{
    if(!this.title.trim()||!this.synopsis.trim()){this.error='Le titre et le synopsis sont obligatoires.';return;}
    this.busy=true;this.error='';this.message='';
    try{
      const created=await firstValueFrom(this.http.post<{id:string}>('/api/movies',{title:this.title.trim(),releaseYear:this.year,sourceSynopsis:this.synopsis.trim()}));
      this.movieId=created.id;await this.loadMovie();this.message='Film enregistré. Tu peux lancer une génération.';
    }catch(e){this.showError(e);}finally{this.busy=false;}
  }
  async loadMovie():Promise<void>{
    if(!this.movieId.trim())return;
    this.error='';
    try{
      this.movie=await firstValueFrom(this.http.get<Movie>('/api/movies/'+encodeURIComponent(this.movieId.trim())));
      localStorage.setItem('movie-factory-last-movie',this.movie.id);
      await this.refreshEpisodes();
    }catch(e){this.movie=null;this.episodes=[];this.selected=null;this.stopPolling();this.showError(e);}
  }
  async generate():Promise<void>{
    if(!this.movie||this.busy)return;
    this.busy=true;this.error='';this.message='';
    try{
      const started=await firstValueFrom(this.http.post<{episodeId:string}>('/api/movies/'+this.movie.id+'/generate',{}));
      await this.refreshEpisodes();
      this.selected=this.episodes.find(e=>e.id===started.episodeId)??null;
      this.message='Génération lancée. Des crédits OpenAI seront utilisés.';
      this.startPolling();
    }catch(e){this.showError(e);}finally{this.busy=false;}
  }
  async refreshEpisodes():Promise<void>{
    if(!this.movie)return;
    try{
      this.episodes=(await firstValueFrom(this.http.get<Episode[]>('/api/movies/'+this.movie.id+'/episodes'))).reverse();
      if(this.selected)this.selected=this.episodes.find(e=>e.id===this.selected?.id)??null;
      if(!this.selected)this.selected=this.episodes[0]??null;
      if(this.episodes.some(e=>this.isRunning(e.status)))this.startPolling();else this.stopPolling();
    }catch(e){this.showError(e);this.stopPolling();}
  }
  isRunning(s:string):boolean{return ['QUEUED','GENERATING','GENERATING_AUDIO','GENERATING_IMAGES','RENDERING'].includes(s);}
  isReady(s:string):boolean{return s==='READY_FOR_REVIEW'||s==='APPROVED';}
  videoUrl(e:Episode):string{return '/api/episodes/'+e.id+'/video';}
  private startPolling():void{if(!this.timer)this.timer=setInterval(()=>{void this.refreshEpisodes();},5000);}
  private stopPolling():void{if(this.timer)clearInterval(this.timer);this.timer=null;}
  private showError(e:unknown):void{
    if(e instanceof HttpErrorResponse){this.error='Erreur HTTP '+e.status+' : '+(typeof e.error?.error==='string'?e.error.error:e.message);}
    else this.error=e instanceof Error?e.message:'Une erreur est survenue.';
  }
}
