package org.peterbaldwin.vlcremote.rezka;

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
        String[] split = subtitles.split(",");
        List<RezkaSubtitle> result = new ArrayList<>();
        for(String subtitle: split) {
            Matcher matcher = QUALITY_PATTERN.matcher(subtitle);

            if(matcher.find()) {
                String label = matcher.group(1);
                String link = subtitle.substring(matcher.end());

                result.add(new RezkaSubtitle(label, link));
            }
        }

        return result;
    }
}
