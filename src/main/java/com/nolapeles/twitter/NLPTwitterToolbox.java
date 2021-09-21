package com.nolapeles.twitter;

import twitter4j.*;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class has several utilities to automate tasks related to your followers.
 **/
public class NLPTwitterToolbox {
    private static final int MAX_TWEET_LENGTH = 140;
    private String SCREEN_NAME;
    private Twitter twitter;

    private boolean FOLLOW_FRIDAY_ON;
    private boolean FOLLOW_MENTIONS;

    private Set<Long> FRIENDS;
    private Map<Long, User> STATUSES;

    private long loadFriendCursor;

    /**
     * The property files we pass to the program are stored here.
     */
    private Properties props;

    /**
     * The file where we serialize the IDs of the people we follow
     */
    private File fFriends;

    /**
     * The file where we serialize known friend statuses so we don't have to request these statuses
     * during the next 3 months.
     */
    private File fStatuses;

    public static void main(String[] args) {

        if (args == null || args.length == 0) {
            System.out.println("ERROR: Please specify at least one twitter4j.properties file.\n");
            return;
        }

        final Thread[] threads = new Thread[args.length];
        int i = 0;
        for (final String propfile : args) {
            try {
                threads[i] = new Thread(() -> {
                    try {
                        //getResponsesToTweets(propfile, new long[]{1334609940627984387l, 1334194103572078592l});
                        serviceAccount(propfile);
                    } catch (Exception e) {
                        System.out.println("Failed servicing account " + propfile);
                        e.printStackTrace();
                    }
                });
                threads[i].start();
                i++;
            } catch (Exception e) {
                continue;
            }
        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Waiting for all threads to finish.");
    }

    /**
     * Takes the name of a twitter4j.prop file and performs on the account for that property file:
     * > follow back new users that have mentioned us recently.
     * > follow friday (if it's a friday)
     *
     * @param propfile
     */
    public static void serviceAccount(String propfile) throws Exception {
        File properties = new File(propfile);

        if (!properties.exists() || !properties.isFile() || !properties.canRead()) {
            System.out.println("ERROR: " + propfile + " is not a valid twitter4j.propertis file.\n");
        }

        NLPTwitterToolbox toolbox = new NLPTwitterToolbox(properties);

        // get all the latest mentions to us, and let's play with this.
        List<Status> statuses = null;
        try {
            statuses = toolbox.getMentions();
        } catch (TwitterException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        // Follow back those that mentioned you, if you're not following them
        // already
        toolbox.unfollowInactive();
        toolbox.tryFollowNewUsers(statuses);
        toolbox.sendFollowFridayShoutout(statuses);

        /**
         if (propfile.equals("twitter4j.properties.punsr")) {
         toolbox.followUEDPlayers();
         }
         */
    }

    /**
     * Unfollow users that have been inactive for 3 months.
     */
    private void unfollowInactive() {
        Iterator<Long> iterator = FRIENDS.iterator();

        Calendar threeMonthsAgo = Calendar.getInstance();
        threeMonthsAgo.add(Calendar.MONTH, -3);

        while (iterator.hasNext()) {
            Long friendId = iterator.next();

            try {

                //unfollow inactive, get user out of FRIENDS.
                //TODO: Save statuses here. Whenever the saved status is too old, then we check again
                //to update and see if it's worth it not following this guy, otherwise, if we know he's
                //been active, we don't need to get the latest status.
                User friend = getUser(friendId);

                if (friend != null && friend.getStatus() != null && friend.getStatus().getCreatedAt().before(threeMonthsAgo.getTime())) {
                    iterator.remove();
                    unfollow(friend);

                    System.out.println("@" + SCREEN_NAME + " unfollows inactive @" + friend.getScreenName() + " Last Tweet was on [" + friend.getStatus().getCreatedAt() + "]");
                } else if (friend != null) {
                    //System.out.println("Keeping @" + friend.getScreenName() + " for @" + SCREEN_NAME);
                }
            } catch (Exception e) {
                try {
                    unfollow(friendId);
                } catch (TwitterException e1) {
                }
                e.printStackTrace();
            }
        }

        saveFriends();
    }

    private void unfollow(User friend) throws TwitterException {
        unfollow(friend.getId());
    }

    private void unfollow(long id) throws TwitterException {
        twitter.destroyFriendship(id);
        FRIENDS.remove(id);
        STATUSES.remove(id);
        saveStatuses();
        saveFriends();
    }

    private User getUser(Long friendId) throws TwitterException {
        User user = STATUSES.get(friendId);

        Calendar threeMonthsAgo = Calendar.getInstance();
        threeMonthsAgo.add(Calendar.MONTH, -3);

        if (user != null && user.getStatus() != null && user.getStatus().getCreatedAt().after(threeMonthsAgo.getTime())) {
            //System.out.println("Got user @" + user.getScreenName() + " from disk.");
            return user;
        }

        // last resort, ask twitter about this guy.
        try {
            user = twitter.showUser(friendId);
        } catch (TwitterException e) {
            return null;
        }

        STATUSES.put(friendId, user);

        /**
         * Possible Disk Bottle Neck, fix it when it happens. 
         * Doing this now to ensure we keep user statuses even if the program
         * stops during a run, so we don't have to use another twitter request
         * for the same data on the next run.
         */
        saveStatuses();

        return user;
    }

    private void sendFollowFridayShoutout(List<Status> statuses) {

        if (!FOLLOW_FRIDAY_ON) {
            System.out.println("Follow friday turned off for " + SCREEN_NAME);
            return;
        }

        boolean isFriday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
        if (!isFriday) {
            System.out.println("Follow friday aborted, not friday");
            return;
        }

        //TODO: Copy this code to generalize this pattern.
        //We're basically converting a list with repeated objects
        //into a sorted set based on the number of appearances. (histogram)

        List<User> usersToRecommend = new ArrayList<User>();
        final Map<User, Integer> userInteractions = new HashMap<User, Integer>();

        for (Status status : statuses) {
            User user = status.getUser();
            if (userInteractions.containsKey(user)) {
                userInteractions.put(user, 1 + userInteractions.get(user));
            } else if (!user.getScreenName().equalsIgnoreCase(SCREEN_NAME)) {
                userInteractions.put(user, 1);
                usersToRecommend.add(user);
            }
        }

        Collections.sort(usersToRecommend, new Comparator<User>() {
            public int compare(User u1, User u2) {
                return userInteractions.get(u2) - userInteractions.get(u1);
            }
        });

        //Now start preparing the twitts with the users, recommend the best ones first.
        List<String> tweets = new ArrayList<String>();

        String shoutoutString = (isFriday) ? "#FF" : "#Follow ";

        StringBuilder tempTweet = new StringBuilder(shoutoutString);
        for (User user : usersToRecommend) {
            if ((tempTweet.length() + user.getScreenName().length() + " @".length()) <= MAX_TWEET_LENGTH) {
                tempTweet.append(" @" + user.getScreenName());
            } else {
                tweets.add(tempTweet.toString());
                tempTweet = new StringBuilder(shoutoutString);
            }
        }

        //sometimes there's material for only one tweet.
        String possibleShortRecommendation = tempTweet.toString();
        if (!possibleShortRecommendation.equals(shoutoutString)) {
            tweets.add(possibleShortRecommendation);
        }

        //Tweet away!
        for (String tweet : tweets) {
            try {
                //System.out.println("Will send: " + tweet);
                twitter.updateStatus(tweet);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    NLPTwitterToolbox(File propertyFile) {
        this(propertyFile, true, true);
    }

    NLPTwitterToolbox(File propertyFile, boolean initFriends, boolean initStatuses) {
        try {
            loadProperties(propertyFile);
            initTwitter();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        if (initFriends) {
            initFriends();
        }

        if (initStatuses) {
            initStatuses();
        }
    }

    private void loadProperties(File propertyFile) throws Exception {
        String[] expectedPropertyKeys = new String[]{"debug", "oauth.consumerKey", "oauth.consumerSecret", "oauth.accessToken", "oauth.accessTokenSecret", "username", "followFriday"};

        props = new Properties();
        props.load(new FileInputStream(propertyFile));

        // make sure all expectedPropertyKeys are there.
        for (String key : expectedPropertyKeys) {
            if (!props.containsKey(key)) {
                throw new Exception("ERROR: Missing '" + key + "' property on property file - " + propertyFile.getAbsolutePath());
            }
        }

        SCREEN_NAME = props.getProperty("username");

        String strFFProp = props.getProperty("followFriday").trim().toLowerCase();
        FOLLOW_FRIDAY_ON = (strFFProp.equals("1") || strFFProp.equals("true") || strFFProp.equals("on"));

        String strFollowMentions = props.getProperty("followMentions");
        FOLLOW_MENTIONS = strFollowMentions != null && (strFollowMentions.equals("1") || strFollowMentions.equals("true") || strFollowMentions.equals("on"));
    }

    private void initStatuses() {
        fStatuses = new File(SCREEN_NAME + ".statuses.dat");
        loadStatuses();
    }

    /**
     * This will load the statuses from disk
     */
    @SuppressWarnings("unchecked")
    private void loadStatuses() {
        if (!fStatuses.exists() || fStatuses.length() == 0) {
            STATUSES = new HashMap<>();
        } else {
            try {
                FileInputStream fis = new FileInputStream(fStatuses);
                ObjectInputStream ois = new ObjectInputStream(fis);
                STATUSES = (HashMap<Long, User>) ois.readObject();
                ois.close();
            } catch (Exception e) {
                STATUSES = new HashMap<>();
                System.out.println(fStatuses + " !!!");
                e.printStackTrace();
                return;
            }
        }
    }

    private void saveStatuses() {
        ObjectOutputStream oos = null;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(fStatuses);
            oos = new ObjectOutputStream(fos);

            try {
                oos.writeObject(STATUSES);
                oos.flush();
                oos.close();
                //System.out.println("Saved " + STATUSES.size() + " user statuses for @" + SCREEN_NAME);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {

        }
    }

    private void initFriends() {
        FRIENDS = new HashSet<Long>();
        fFriends = new File(SCREEN_NAME + ".friends.txt");
        loadFriends();
    }

    private void loadFriends() {
        if (!fFriends.exists() || fFriends.length() == 0) {
            getFriendsFromInternet();
            saveFriends();
        } else {
            try {
                FileInputStream fis = new FileInputStream(fFriends);
                ObjectInputStream ois = new ObjectInputStream(fis);
                while (true) {
                    try {
                        FRIENDS.add(ois.readLong());
                    } catch (Exception e) {
                        fis.close();
                        break;
                    }
                }
            } catch (Exception e) {
                return;
            }
        }
    }

    /**
     * Serializes the Twitter IDs of friends into a file.
     */
    private void saveFriends() {
        ObjectOutputStream oos = null;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(fFriends);
            oos = new ObjectOutputStream(fos);
            for (Long lid : FRIENDS) {
                oos.writeLong(lid);
            }

            try {
                oos.flush();
                oos.close();
                System.out.println("Saved " + FRIENDS.size() + " friends for @" + SCREEN_NAME);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {

        }
    }

    private void getFriendsFromInternet() {
        loadFriendCursor = -1;
        while (getNextFriendsIDs()) {
        }
    }

    private boolean getNextFriendsIDs() {
        try {
            IDs friendsIDs = twitter.getFriendsIDs(SCREEN_NAME, loadFriendCursor);
            long[] iDs = friendsIDs.getIDs();
            for (long lid : iDs) {
                FRIENDS.add(lid);
            }
            loadFriendCursor = friendsIDs.getNextCursor();
            return friendsIDs.hasNext();
        } catch (TwitterException e) {
            return false;
        }
    }

    private void initTwitter() throws TwitterException {
        Configuration configuration = new ConfigurationBuilder().setOAuthConsumerKey(props.getProperty("oauth.consumerKey")).setOAuthConsumerSecret(props.getProperty("oauth.consumerSecret"))
                .setOAuthAccessToken(props.getProperty("oauth.accessToken")).setOAuthAccessTokenSecret(props.getProperty("oauth.accessTokenSecret")).build();

        twitter = new TwitterFactory(configuration).getInstance();
        System.out.println("Verifying credentials for " + props.getProperty("username"));
        twitter.verifyCredentials();
    }

    /**
     * Won't follow if FOLLOW_MENTIONS=false
     */
    private void tryFollowNewUsers(List<Status> statuses) {
        if (!FOLLOW_MENTIONS) {
            System.out.println("followNewUsers() aborted, not followMentions=false");
            return;
        }
        for (Status status : statuses) {
            long userid;

            // don't follow your tail!
            if (status.getUser().getScreenName().equalsIgnoreCase(SCREEN_NAME)) {
                continue;
            }

            if (!FRIENDS.contains(userid = status.getUser().getId())) {
                try {
                    System.out.println(SCREEN_NAME + " is Following @" + status.getUser().getScreenName());
                    twitter.createFriendship(userid, true);
                    FRIENDS.add(userid);
                } catch (TwitterException e) {
                }
            }
        }
        saveFriends();
    }

    private List<Status> getMentions() throws TwitterException {
        Paging paging = new Paging();
        paging.setCount(100);
        List<Status> statuses = twitter.getMentionsTimeline(paging);
        return statuses;
    }

    List<Status> findTweets(String... orKeywords) {
        //if (orKeywords == null || orKeywords.length == 0) {
        //   return new ArrayList<>();
        // }
        List<Status> tweets = new ArrayList<Status>();
        try {
            int page = 1;
            while (page < 100) {
                final ResponseList<Status> userTimeline = twitter.getUserTimeline(new Paging(page++, 100));
                System.out.println("timeline contained " + userTimeline.size() + " statuses");
                for (Status s : userTimeline) {
                    if (anyKeyWordIn(s.getText(), orKeywords)) {
                        System.out.println("Adding -> " + s.getText());
                        tweets.add(s);
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return tweets;
    }

    private boolean anyKeyWordIn(String text, String[] orKeywords) {
        String textLower = text.toLowerCase();
        for (String keyword : orKeywords) {
            if (textLower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    void deleteTweets(List<Status> statuses) {
        if (statuses == null || statuses.size() == 0) {
            return;
        }
        int i = 1;
        for (Status status : statuses) {
            try {
                twitter.destroyStatus(status.getId());
                System.out.println("Destroyed(" + i + "): [" + status.getId() + "] " + status.getText());
                i++;
            } catch (TwitterException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Given a list of tweet ids, parses the mention stream to the sender's account whenever the
     * status are responding to any of the matching tweet ids.
     */
    public static List<Status> getResponsesToTweets(String propfile, long[] tweetsBeingRespondedTo) {
        File properties = new File(propfile);
        if (!properties.exists() || !properties.isFile() || !properties.canRead()) {
            System.out.println("ERROR: " + propfile + " is not a valid twitter4j.propertis file.\n");
        }

        NLPTwitterToolbox toolbox = new NLPTwitterToolbox(properties);

        try {
            HashMap<Long, Integer> suggestionSentBy = new HashMap<>();
            int processed = 0;
            Paging paging = new Paging(1, 200);
            ResponseList<Status> mentions = toolbox.twitter.getMentionsTimeline(paging);
            List<Status> results = new ArrayList<>();
            while (mentions.size() > 0 && processed < 20000) {
                mentions.stream().
                        filter(s -> {
                            long senderId = s.getUser().getId();
                            if (suggestionSentBy.containsKey(senderId)) {
                                suggestionSentBy.put(senderId, 1 + suggestionSentBy.get(senderId));
                            } else {
                                suggestionSentBy.put(senderId, 1);
                            }
                            if (suggestionSentBy.get(senderId) > 3) {
                                return false;
                            }
                            return Arrays.stream(tweetsBeingRespondedTo).anyMatch(
                                    targetTweetId -> s.getInReplyToStatusId() == targetTweetId);
                        }).
                        forEach(s ->
                        {
                            try {
                                System.out.println(s.getText().toLowerCase().replace("@" + toolbox.twitter.getScreenName().toLowerCase(), ""));
                            } catch (TwitterException e) {
                                e.printStackTrace();
                            }
                            results.add(s);
                        });
                paging.setPage(paging.getPage() + 1);
                System.out.println("== Page " + paging.getPage() + " ==================================");
                processed += mentions.size();
                Thread.sleep(5000);
                toolbox.twitter.getMentionsTimeline(paging);
            }
            System.out.println("Total API Calls: " + paging);
            System.out.println("Total Processed Tweets: " + processed);
            return results;
        } catch (TwitterException | InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Status getTweet(long statusID) {
        try {
            return twitter.showStatus(statusID);
        } catch (TwitterException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Given a Tweet created by OUR user, it returns a list of replies
     * It does this by performing a search using to:us since_id:<id_of_tweet> then
     * filtering out the mentions that are in response to the given tweet id.
     * */
    public List<Status> getRepliesToMyTweet(Status tweet, int maxReplies) {
        List<Status> tweets = new ArrayList<>();
        try {
            Query query = new Query("to:" + SCREEN_NAME + " since_id:" + String.valueOf(tweet.getId()));
            QueryResult queryResult;
            do {
                queryResult = twitter.search(query);
                List<Status> responses = queryResult.getTweets();
                Stream<Status> stream = responses.stream();
                List<Status> filtered = stream.filter(s -> s.getInReplyToStatusId() == tweet.getId()).collect(Collectors.toList());
                tweets.addAll(filtered);
            } while ((query = queryResult.nextQuery()) != null && tweets.size() < maxReplies);
            return tweets;
        } catch (TwitterException e) {
            e.printStackTrace();
            return tweets;
        }
    }

    public String getScreenNameLowercase() {
        return SCREEN_NAME.toLowerCase(Locale.ROOT);
    }
}
