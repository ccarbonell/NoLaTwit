package com.nolapeles.twitter;

import twitter4j.Status;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Give it a list of keywords and delete tweets matching it from a given twitter account timeline.
 * Created by gubatron on 10/18/16.
 */
public class HistoryCleaner {

    public static void main(String[] args) throws IOException {
        if (args == null || args.length == 0) {
            System.err.println("No Twitter Account property file given.");
            return;
        }
        File f = new File(args[0]);
        if (!f.isFile()) {
            System.err.println("<"+args[0]+"> is not a file." );
        }

        if (!f.exists()) {
            System.err.println("File <"+args[0]+"> does not exist");
            return;
        }

        NLPTwitterToolbox toolbox = new NLPTwitterToolbox(f);
        List<Status> statuses = toolbox.findTweets("stupid","things","you", "have", "said", "here");
        System.out.println("Destroying " + statuses.size() + " tweets");
        toolbox.deleteTweets(statuses);
    }
}
