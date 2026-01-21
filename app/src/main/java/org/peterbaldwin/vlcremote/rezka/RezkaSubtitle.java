package org.peterbaldwin.vlcremote.rezka;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by sfedorov on 17-Jan-26.
 */

public class RezkaSubtitle {
    private static final Pattern QUALITY_PATTERN = Pattern.compile("^\\[(.*)\\]");

    private final String label;
    private final String link;

    public RezkaSubtitle(String label, String link) {
        this.label = label;
        this.link = link;
    }

    public static List<RezkaSubtitle> parse(String subtitles) {
        List<RezkaSubtitle> result = new ArrayList<>();
        try{
            String[] split = subtitles.split(",");
            for(String subtitle: split) {
                Matcher matcher = QUALITY_PATTERN.matcher(subtitle);

                if(matcher.find()) {
                    String label = matcher.group(1);
                    String link = subtitle.substring(matcher.end());

                    result.add(new RezkaSubtitle(label, link));
                }
            }
        }catch (RuntimeException ex) {
            Log.e("VLC", "cannot parse subtitles: " + subtitles, ex);
            return null;
        }

        return result;
    }

    public String getLabel() {
        return label;
    }

    public String getLink() {
        return link;
    }

    @Override
    public String toString() {
        return link;
    }

    public String getExtension() {
        int dot = link.lastIndexOf('.');
        if (dot <= 0 || dot == link.length() - 1 || link.length() - dot > 10) return ".tmp";
        return link.substring(dot + 1).toLowerCase();
    }
}
