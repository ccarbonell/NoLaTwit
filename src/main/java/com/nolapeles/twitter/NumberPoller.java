package com.nolapeles.twitter;

import twitter4j.Status;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.System.err;
import static java.lang.System.out;


/**
 * Given a tweet create a histogram report based on the responses.
 * Valid responses contain a single number from 1 to N, where N are the number of
 * Polled questions
 */
public class NumberPoller {
    /**
     * Pass the status (Tweeet) ID
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            errorln("NumberPoller: status id (tweet id) missing. Exiting.");
            System.exit(-1);
            return;
        }
        long statusID = -1;
        try {
            statusID = Long.parseLong(args[0]);
        } catch (Throwable t) {
            errorln("NumberPoller: Invalid status id:" + args[0]);
            System.exit(-1);
        }

        NLPTwitterToolbox npl = new NLPTwitterToolbox(new File("twitter4j.properties.diariobitcoin"));
        String screenName = npl.getScreenNameLowercase();
        println("NumberPoller: statusID " + statusID);
        File repliesFile = new File("number_poller_replies.txt");

        // If the file does not exist or it's older than 1 hour, let's fetch all responses from twitter and save them
        if (!repliesFile.exists() || (System.currentTimeMillis() - repliesFile.lastModified()) > 60000 * 60) {
            Status tweet = npl.getTweet(statusID);
            List<Status> replies = npl.getRepliesToMyTweet(tweet, 1000);
            saveRepliesToFile(statusID, replies, repliesFile);
        }

        List<String> validReplies = loadValidReplies(screenName, repliesFile);
        int totalVotes = validReplies.size();
        Map<String, Integer> histogram = new HashMap<>();
        validReplies.forEach(s -> {
            if (histogram.containsKey(s)) {
                histogram.put(s, 1 + histogram.get(s));
            } else {
                histogram.put(s, 1);
            }
        });
        histogram.keySet().stream().sorted().forEach(k -> {
            double p = 100 * histogram.get(k) / (double) totalVotes;
            println(k + " -> " + histogram.get(k) + " (" + p + "%)");
        });
    }

    private static List<String> loadValidReplies(String screenName, File repliesFile) {
        List<String> validReplies = new ArrayList<>();
        try {
            List<String> stringLines = Utils.getStringLines(repliesFile);
            validReplies.addAll(stringLines.
                    stream().
                    map(status -> status.toLowerCase(Locale.ROOT).trim().
                            replace("@" + screenName, "").trim().
                            replace("#", "")).
                    filter(s -> isValidPollResponse(s, 1, 10)).
                    collect(Collectors.toList()));
            println("NumberPoller: " + validReplies.size() + " valid responses");
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return validReplies;
    }

    private static boolean isValidPollResponse(String status, int minValidResponse, int maxValidResponse) {
        Pattern pattern = Pattern.compile("^(\\d+)$");
        Matcher matcher = pattern.matcher(status);
        if (!matcher.find()) {
            return false;
        }
        String number = matcher.group(1);
        try {
            int n = Integer.parseInt(number);
            return minValidResponse <= n && n <= maxValidResponse;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Overwrites the file
     */
    private static List<Status> saveRepliesToFile(final long statusID, List<Status> replies, File repliesFile) {
        try {
            FileWriter writer = new FileWriter(repliesFile);
            replies.stream().forEach(s -> {
                String text = s.getText().replaceAll("\n", "");
                try {
                    writer.write(text + "\n");
                    writer.flush();
                    println("NumberPoller: writing response to " + String.valueOf(statusID) + ":[" + text + "]");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return replies;
    }

    private static void println(String x) {
        out.println(x);
    }

    private static void errorln(String x) {
        err.println(x);
    }
}
