package org.peterbaldwin.vlcremote.rezka;


import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RezkaStreamAddress {
    private static final String STREAM_SEPARATOR = "//_//";
    private static final List<String> TRASH_LIST = Arrays.asList("$$#!!@#!@##", "^^^!@##!!##", "####^!!##!@@", "@@@@@!##!^^^", "$$!!@$$@^!@#$$@");
    private static final Pattern QUALITY_PATTERN = Pattern.compile("^\\[(\\d+p(?:\\s\\w*)?)\\]");

    private final String quality;
    private final String mp4;

    public RezkaStreamAddress(String quality, String mp4) {
        this.quality = quality;
        this.mp4 = mp4;
    }

    public static String decode0(String streams) {
        streams = streams.substring(2);
        for(int i =0;i<2;++i) {
            streams = streams.replaceAll(STREAM_SEPARATOR, "");
            for(String trash: TRASH_LIST) {
                String toErase = Base64.encodeToString(trash.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                streams = streams.replaceAll(toErase, "");
            }
        }
        return streams;
    }

    public static String decode(String streams) {
        byte[] bytesIn = decode0(streams).getBytes(StandardCharsets.UTF_8);
        byte[] bytesOut = Base64.decode(bytesIn, Base64.DEFAULT);
        return new String(bytesOut, StandardCharsets.UTF_8);
    }


    public static List<RezkaStreamAddress> parse(String input) {
        String decoded = decode(input);
        String[] split = decoded.split(",");
        List<RezkaStreamAddress> streams = new ArrayList<>();
        for(String stream: split) {
            Matcher matcher = QUALITY_PATTERN.matcher(stream);

            if(matcher.find()) {
                String quality = matcher.group(1);
                String[] urls = stream.substring(matcher.end()).split(" or ");
                String url = urls[0];
                for (String url1 : urls) {
                    if (url1.endsWith(".mp4")) url = url1;
                }
                streams.add(new RezkaStreamAddress(quality, url));
            }
        }

        return streams;
    }

    public String getQuality() {
        return quality;
    }

    public String getMp4() {
        return mp4;
    }

    public String getHls() {
        if(mp4.endsWith(".mp4")) {
            return mp4 + ":hls:manifest.m3u8";
        }else{
            return mp4;
        }
    }

    @Override
    public String toString() {
        return mp4;
    }
}
