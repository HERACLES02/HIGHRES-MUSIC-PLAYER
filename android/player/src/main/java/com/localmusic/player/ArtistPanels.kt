package com.localmusic.player

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmusic.core.*
import com.localmusic.data.LibraryExtras

@Composable
private fun SourceLink(label:String,url:String){
    val handler=LocalUriHandler.current
    TextButton(onClick={runCatching{handler.openUri(url)}}){Text(label,maxLines=2,overflow=TextOverflow.Ellipsis)}
}

@Composable
fun ArtworkCredits(detail:LibraryExtras?){
    detail?.artworkCredit?.let{Text("Artwork: $it",style=MaterialTheme.typography.bodySmall)}
    detail?.artworkSourceUrl?.let{SourceLink("Artwork source",it)}
}

@Composable
fun ArtistPage(name:String,tracks:List<Track>,profile:ArtistProfile?,store:MusicStore?,ready:Boolean,ui:ListeningToolsUi,
    send:(String,Bundle)->Unit,play:(List<Track>,Int,Boolean)->Unit,details:(Track)->Unit,addToPlaylist:(Track)->Unit,album:(Track)->Unit){
    var editingNote by rememberSaveable(name){mutableStateOf(false)}
    var note by rememberSaveable(name){mutableStateOf("")}
    var photoTarget by rememberSaveable{mutableStateOf<String?>(null)}
    var clear by rememberSaveable(name){mutableStateOf(false)}
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        val target=photoTarget;photoTarget=null
        if(uri!=null&&target!=null)send(PlaybackService.ARTIST_PHOTO,Bundle().apply{putString("name",target);putString("uri",uri.toString())})
    }
    val albums=remember(tracks){tracks.groupBy{(it.tags.album.orEmpty()) to (it.tags.albumArtist?:it.tags.artists.joinToString(", "))}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{CoverArt(profile?.imagePath,name,Modifier.fillMaxWidth().height(180.dp));Text(name,Modifier.padding(top=12.dp),style=MaterialTheme.typography.headlineMedium)
            profile?.description?.takeIf{it.isNotBlank()}?.let{Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text("${tracks.size} songs · ${albums.size} albums",style=MaterialTheme.typography.bodyMedium)
        }
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(enabled=ready&&tracks.isNotEmpty(),onClick={play(tracks,0,false)}){Icon(PlayerGlyph.Play,null);Spacer(Modifier.width(6.dp));Text("Play artist")}
            FilledTonalButton(enabled=ready&&tracks.isNotEmpty(),onClick={play(tracks,0,true)}){Icon(PlayerGlyph.Shuffle,null);Spacer(Modifier.width(6.dp));Text("Shuffle")}}
        }
        item{Text("About ${profile?.name?:name}",style=MaterialTheme.typography.titleLarge)
            Text(profile?.biography?.takeIf{it.isNotBlank()}?:"No saved biography. Find an artist match or add your own note.")
            if(profile!=null){Text("Saved for offline use · ${java.time.Instant.ofEpochMilli(profile.savedAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",style=MaterialTheme.typography.labelSmall)
                profile.biographyLicense?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                if(profile.biographyLicense?.contains("CC BY-SA")==true)SourceLink("CC BY-SA 4.0 license","https://creativecommons.org/licenses/by-sa/4.0/")
                profile.biographyUrl?.let{SourceLink("Biography source & contributors",it)}
                profile.identityUrl?.let{SourceLink("Selected ${profile.providerName} match",it)}
                profile.imageCredit?.let{Text("Image: $it",style=MaterialTheme.typography.bodySmall)}
                profile.imageLicense?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                profile.imageSourceUrl?.let{SourceLink("Image source & license",it)}
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(enabled=ready&&store!=null,onClick={ui.resetLookup();ui.artistTarget=name}){Text(if(profile==null)"Find artist" else "Change / refresh match")}
                TextButton(enabled=ready,onClick={note=profile?.biography.orEmpty();editingNote=true}){Text("Edit note")}
                TextButton(enabled=ready,onClick={photoTarget=name;photo.launch(arrayOf("image/*"))}){Text("Choose photo")}
                if(profile!=null)TextButton(enabled=ready,onClick={clear=true}){Text("Remove match")}
            }
            Text("Online searches run only when you request them. Saving a match does not edit your music tags.",style=MaterialTheme.typography.bodySmall)
        }
        item{HorizontalDivider();Text("Albums",style=MaterialTheme.typography.titleLarge)}
        items(albums.entries.toList(),key={it.key.toString()}){entry->val first=entry.value.first()
            ListItem(modifier=Modifier.clickable{album(first)},leadingContent={CoverArt(store?.artwork(first.id),first.tags.album.orEmpty(),Modifier.size(58.dp))},
                headlineContent={Text(first.tags.album?:"Unknown album",maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={Text("${entry.value.size} songs")},
                trailingContent={TextButton(enabled=ready&&store!=null,onClick={ui.resetLookup();ui.coverTargets=entry.value}){Text("Find cover")}})
        }
        item{Text("Songs",style=MaterialTheme.typography.titleLarge)}
        items(tracks,key={it.id.value.toString()}){track->ListItem(modifier=Modifier.clickable(enabled=ready&&track.availability==Availability.AVAILABLE){play(tracks,tracks.indexOf(track),false)},
            leadingContent={CoverArt(store?.artwork(track.id),track.tags.title,Modifier.size(48.dp))},headlineContent={Text(track.tags.title,maxLines=2,overflow=TextOverflow.Ellipsis)},
            supportingContent={Text(track.tags.album.orEmpty(),maxLines=1,overflow=TextOverflow.Ellipsis)},trailingContent={TrackMenu(track,ready,send,{addToPlaylist(track)},{details(track)})})}
    }
    if(editingNote)AlertDialog(onDismissRequest={editingNote=false},title={Text("Artist note")},text={OutlinedTextField(note,{note=it.take(6000)},modifier=Modifier.fillMaxWidth().heightIn(min=160.dp,max=340.dp),label={Text("About this artist")})},
        confirmButton={TextButton(enabled=ready,onClick={send(PlaybackService.ARTIST_NOTE,Bundle().apply{putString("name",name);putString("text",note)});editingNote=false}){Text("Save")}},dismissButton={TextButton(onClick={editingNote=false}){Text("Cancel")}})
    if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("Remove saved artist match?")},text={Text("The cached biography and chosen artist photo will be removed. Your songs and album covers stay.")},
        confirmButton={TextButton(enabled=ready,onClick={send(PlaybackService.ARTIST_CLEAR,Bundle().apply{putString("name",name)});clear=false}){Text("Remove")}},dismissButton={TextButton(onClick={clear=false}){Text("Cancel")}})
}

@Composable
fun ArtistLookupDialogs(ui:ListeningToolsUi,store:MusicStore?,ready:Boolean,send:(String,Bundle)->Unit){
    val target=ui.artistTarget
    if(target!=null){
        var query by rememberSaveable(target){mutableStateOf(target)}
        var wikipedia by rememberSaveable(target){mutableStateOf(false)}
        fun close(){ui.artistTarget=null;ui.resetLookup()}
        val review=ui.artistReview
        AlertDialog(onDismissRequest={close()},title={Text(if(review==null)"Find an artist" else "Review artist match")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(review==null){
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilterChip(!wikipedia,{wikipedia=false;ui.resetLookup()},enabled=!ui.lookupBusy,label={Text("MusicBrainz")})
                    FilterChip(wikipedia,{wikipedia=true;ui.resetLookup()},enabled=!ui.lookupBusy,label={Text("Wikipedia")})
                }
                OutlinedTextField(query,{query=it.take(300)},enabled=!ui.lookupBusy,label={Text("Artist name")},singleLine=true)
                Button(enabled=!ui.lookupBusy&&store!=null&&query.isNotBlank(),onClick={ui.artists=emptyList();ui.lookup{ui.artistReview=null;ui.artists=store!!.artistLookup.searchArtists(query,wikipedia)}}){Text(if(wikipedia)"Search Wikipedia" else "Search MusicBrainz")}
                Text(if(wikipedia)"Searches English Wikipedia directly. Review the article to make sure it is your artist. Credited Commons photos are included when available."
                    else "Sends this query to MusicBrainz. A selected match can then load a linked Wikipedia biography and credited Commons image.",style=MaterialTheme.typography.bodySmall)
                ui.artists.forEach{candidate->OutlinedCard(onClick={if(!ui.lookupBusy&&store!=null)ui.lookup{ui.artistReview=store.artistLookup.previewArtist(candidate.id)}}){Column(Modifier.fillMaxWidth().padding(12.dp)){
                    Text(candidate.name,style=MaterialTheme.typography.titleMedium);Text(candidate.description.ifBlank{"No distinguishing details"});Text(candidate.id,style=MaterialTheme.typography.labelSmall)
                }}}
                if(ui.artists.isEmpty()&&!ui.lookupBusy&&ui.lookupError.isBlank())Text("Search to see possible matches. Nothing is saved automatically.")
            }else{
                review.image?.let{CoverArt(it,review.profile.name,Modifier.fillMaxWidth().height(170.dp))}
                Text(review.profile.name,style=MaterialTheme.typography.titleLarge);Text(review.profile.description)
                Text(review.profile.biography.ifBlank{"No linked English biography is available for this artist."})
                review.profile.biographyLicense?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                if(review.profile.biographyLicense?.contains("CC BY-SA")==true)SourceLink("CC BY-SA 4.0 license","https://creativecommons.org/licenses/by-sa/4.0/")
                review.profile.biographyUrl?.let{SourceLink("Biography source",it)}
                review.profile.identityUrl?.let{SourceLink("${review.profile.providerName} identity",it)}
                review.profile.imageCredit?.let{Text("Image credit: $it",style=MaterialTheme.typography.bodySmall)}
                review.profile.imageLicense?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                review.profile.imageSourceUrl?.let{SourceLink("Image source & license",it)}
                TextButton(enabled=!ui.lookupBusy,onClick={ui.artistReview=null}){Text("Choose a different match")}
            }
            if(ui.lookupBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(ui.lookupError.isNotBlank())Text(ui.lookupError,color=MaterialTheme.colorScheme.error)
        }},confirmButton={if(review!=null)TextButton(enabled=ready&&!ui.lookupBusy,onClick={send(PlaybackService.ARTIST_SAVE,Bundle().apply{putString("name",target);putString("receipt",review.receipt)});close()}){Text("Save for offline use")}},
            dismissButton={TextButton(onClick={close()}){Text("Cancel")}})
    }
    val coverTracks=ui.coverTargets
    if(coverTracks!=null){
        val first=coverTracks.firstOrNull()?:return
        var artist by rememberSaveable(first.id.value.toString()){mutableStateOf(first.tags.albumArtist?:first.tags.artists.joinToString(", "))}
        var album by rememberSaveable(first.id.value.toString()){mutableStateOf(first.tags.album.orEmpty())}
        fun close(){ui.coverTargets=null;ui.resetLookup()}
        val review=ui.coverReview
        AlertDialog(onDismissRequest={close()},title={Text(if(review==null)"Find album artwork" else "Review album cover")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(review==null){
                OutlinedTextField(artist,{artist=it.take(300)},label={Text("Artist")},singleLine=true)
                OutlinedTextField(album,{album=it.take(300)},label={Text("Album")},singleLine=true)
                Button(enabled=store!=null&&!ui.lookupBusy&&artist.isNotBlank()&&album.isNotBlank(),onClick={ui.lookup{ui.releases=store!!.artistLookup.searchCovers(artist,album)}}){Text("Search releases")}
                Text("Searches MusicBrainz. Choose a release to preview a front cover from Cover Art Archive.",style=MaterialTheme.typography.bodySmall)
                ui.releases.forEach{release->OutlinedCard(onClick={if(!ui.lookupBusy&&store!=null)ui.lookup{ui.coverReview=store.artistLookup.previewCover(release.id)}}){Column(Modifier.fillMaxWidth().padding(12.dp)){
                    Text(release.title,style=MaterialTheme.typography.titleMedium);Text(release.artist);Text(listOf(release.date,release.country,release.edition).filter{it.isNotBlank()}.joinToString(" · "));Text(release.id,style=MaterialTheme.typography.labelSmall)
                }}}
                if(ui.releases.isEmpty()&&!ui.lookupBusy)Text("Search to see releases. Some editions may have no available cover.")
            }else{
                CoverArt(review.image,review.release.title,Modifier.fillMaxWidth().height(210.dp))
                Text(review.release.title,style=MaterialTheme.typography.titleLarge);Text(listOf(review.release.artist,review.release.date,review.release.country,review.release.edition).filter{it.isNotBlank()}.joinToString(" · "))
                Text(review.credit,style=MaterialTheme.typography.bodySmall);SourceLink("Release & cover source",review.sourceUrl)
                Text("Use this cover for ${coverTracks.size} songs in the app. Music files stay unchanged.")
                TextButton(onClick={ui.coverReview=null}){Text("Choose another release")}
            }
            if(ui.lookupBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(ui.lookupError.isNotBlank())Text(ui.lookupError,color=MaterialTheme.colorScheme.error)
        }},confirmButton={if(review!=null)TextButton(enabled=ready&&!ui.lookupBusy,onClick={send(PlaybackService.COVER_SAVE,Bundle().apply{putString("receipt",review.receipt);putStringArrayList("ids",ArrayList(coverTracks.map{it.id.value.toString()}))});close()}){Text("Use cover in app")}},
            dismissButton={TextButton(onClick={close()}){Text("Cancel")}})
    }
}
