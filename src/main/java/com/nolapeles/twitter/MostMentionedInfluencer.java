package com.nolapeles.twitter;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MostMentionedInfluencer {
    static int totalMentions = 0;
    static int influencerCount = 0;

    public static void main(String[] args) throws IOException {
        Pattern tweep = Pattern.compile("(@[a-zA-Z0-9_]+)");
        HashMap<String, Integer> histogram = new HashMap<>();
        Utils.getStringLines(new File("src/main/resources/replies.txt")).
                forEach(s -> {
                    Matcher matcher = tweep.matcher(s);
                    while (matcher.find()) {
                        totalMentions++;
                        String nickname = matcher.group(1);
                        if (histogram.containsKey(nickname)) {
                            histogram.put(nickname, 1 + histogram.get(nickname));
                        } else {
                            histogram.put(nickname, 1);
                        }
                    }
                });


        histogram.entrySet().stream().
                sorted((o1, o2) -> Integer.compare(o2.getValue(), o1.getValue())).
                forEach(entry ->
                        System.out.println(++influencerCount + ". " + entry.getValue() + " " + entry.getKey() + " (" + ((100 * entry.getValue()) / totalMentions * 1.0) + " %)"));
    }
}
