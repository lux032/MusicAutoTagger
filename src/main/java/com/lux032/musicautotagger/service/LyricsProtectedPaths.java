package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Immutable effective directory snapshot; follows each owning service's defaults and whitespace semantics. */
final class LyricsProtectedPaths {
    record Entry(String field, Path path, Path real, Object key) {}
    final Path root;
    final List<Entry> entries;
    final boolean export;
    private LyricsProtectedPaths(Path root, List<Entry> entries, boolean export) { this.root=root; this.entries=List.copyOf(entries); this.export=export; }
    static LyricsProtectedPaths capture(MusicConfig c) throws IOException {
        Path root=Path.of(c.getOutputDirectory()).toAbsolutePath().normalize();
        Map<String,String> values=new LinkedHashMap<>();
        values.put("monitorDirectory",c.getMonitorDirectory());
        values.put("recoveryTrashDirectory",blankDefault(c.getRecoveryTrashDirectory(),"data/recovery-trash"));
        values.put("recoveryWorkDirectory",blankDefault(c.getRecoveryWorkDirectory(),root.resolve(".recovery-work").toString()));
        String cover=c.getCoverArtCacheDirectory();
        values.put("coverArtCacheDirectory",cover==null||cover.isEmpty()?root.resolve(".cover_cache").toString():cover);
        values.put("failedDirectory",c.getFailedDirectory()); values.put("partialDirectory",c.getPartialDirectory());
        String cue=c.getCueSplitOutputDir(); values.put("cueSplitOutputDir",cue==null||cue.trim().isEmpty()?null:cue);
        String staging=c.getReviewStagingDirectory(); values.put("reviewStagingDirectory",staging==null||staging.trim().isEmpty()?"data/review-staging":staging.trim());
        List<Entry> entries=new ArrayList<>();
        for(var item:values.entrySet()) {
            if(item.getValue()==null||item.getValue().isEmpty())continue;
            try {
                Path path=Path.of(item.getValue()).toAbsolutePath().normalize(), real=null;Object key=null;
                try {
                    BasicFileAttributes attrs=Files.readAttributes(path,BasicFileAttributes.class);
                    if(!attrs.isDirectory())throw new IOException("not.directory");
                    real=path.toRealPath();key=attrs.fileKey();
                } catch(NoSuchFileException missing) {
                    // A dangling alias cannot prove where future writes will land.
                    for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("unknown.alias");
                }
                entries.add(new Entry(item.getKey(),path,real,key));
            } catch(IOException|RuntimeException e) {throw new IOException("lyrics.backfill.protected.invalid:"+item.getKey(),e);}
        }
        return new LyricsProtectedPaths(root,entries,c.isExportLyricsToFile());
    }
    private static String blankDefault(String value,String fallback) {return value==null||value.isBlank()?fallback:value;}
    void validateRoot() throws IOException {
        for(Entry entry:entries) {
            if(root.startsWith(entry.path())||(entry.real()!=null&&root.toRealPath().startsWith(entry.real())))conflict(entry);
            for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p)&&same(entry,p,Files.readAttributes(p,BasicFileAttributes.class)))conflict(entry);
        }
    }
    private void conflict(Entry entry) throws IOException {throw new IOException("lyrics.backfill.root.conflict:"+entry.field());}
    boolean excluded(Path path,BasicFileAttributes attrs) throws IOException {
        if(Files.isSymbolicLink(path)||(!path.equals(root)&&path.getFileName().toString().startsWith(".")))return true;
        for(Entry entry:entries)if(path.toAbsolutePath().normalize().startsWith(entry.path())||same(entry,path,attrs))return true;
        return false;
    }
    private boolean same(Entry entry,Path path,BasicFileAttributes attrs) throws IOException {
        if(entry.real()==null)return false;
        if(entry.key()!=null&&attrs.fileKey()!=null&&entry.path().getFileSystem().provider()==path.getFileSystem().provider()
            &&entry.path().getFileSystem()==path.getFileSystem()&&entry.key().equals(attrs.fileKey()))return true;
        return path.toRealPath().equals(entry.real());
    }
}
