package com.nolapeles.twitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import twitter4j.IDs;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.User;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

/**
 * This class has several utilities to automate tasks related to your followers.
 * 
 * 
 **/
public class NLPTwitterToolbox {
	private static final int MAX_TWEET_LENGTH = 140;
	private String SCREEN_NAME;
	private Twitter twitter;
	
	private Set<Long> FRIENDS;
	private Map<Long,User> STATUSES;
	
	private long loadFriendCursor;

	/** The property files we pass to the program are stored here. */
	private Properties props;

	/** The file where we serialize the IDs of the people we follow */
	private File fFriends;
	
	/** The file where we serialize known friend statuses so we don't have to request these statuses
	 *  during the next 3 months.
	 */
	private File fStatuses;

	public static void main(String[] args) {

		if (args == null || args.length == 0) {
			System.out
					.println("ERROR: Please specify at least one twitter4j.properties file.\n");
			return;
		}
		
		ExecutorService threadPool= Executors.newFixedThreadPool(args.length);
		
		for (final String propfile : args) {
			threadPool.execute(new Runnable() {
				@Override
				public void run() {
					try {
						serviceAccount(propfile);
					} catch (Exception e) {}
				}
			});			
		}
		
		threadPool.shutdown();

	}

	/**
	 * Takes the name of a twitter4j.prop file and performs on the account for that property file:
	 * > follow back new users that have mentioned us recently.
	 * > follow friday (if it's a friday)
	 * @param propfile
	 */
	public static void serviceAccount(String propfile) {
		File properties = new File(propfile);

		if (!properties.exists() || !properties.isFile()
				|| !properties.canRead()) {
			System.out.println("ERROR: " + propfile
					+ " is not a valid twitter4j.propertis file.\n");
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
		toolbox.followNewUsers(statuses);
		toolbox.sendFollowFridayRecommendations(statuses);
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
				
				if (friend.getStatus().getCreatedAt().before(threeMonthsAgo.getTime())) {
					iterator.remove();
					unfollow(friend);
					
					System.out.println("@"+ SCREEN_NAME+ " unfollows inactive @" + friend.getScreenName() + " Last Tweet was on ["+friend.getStatus().getCreatedAt()+"]");
				} else {
					System.out.println("Keeping @" + friend.getScreenName() + " for @" + SCREEN_NAME);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		saveFriends();
	}

	private void unfollow(User friend) throws TwitterException {
		twitter.destroyFriendship(friend.getId());
		FRIENDS.remove(friend.getId());
		STATUSES.remove(friend.getId());
		saveStatuses();
		saveFriends();
	}

	private User getUser(Long friendId) throws TwitterException {
		User user = STATUSES.get(friendId);
		
		Calendar threeMonthsAgo = Calendar.getInstance();
		threeMonthsAgo.add(Calendar.MONTH, -3);
		
		if (user != null && user.getStatus().getCreatedAt().after(threeMonthsAgo.getTime())) {
			System.out.println("Got user @" + user.getScreenName() + " from disk.");
			return user;
		}
		
		// last resort, ask twitter about this guy.
		user = twitter.showUser(friendId);
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

	private void sendFollowFridayRecommendations(List<Status> statuses) {
		
		if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
			System.out.println("No #FF today.");
			return;
		} else {
			System.out.println("TGI Friday!!!");
		}
		
		//TODO: Copy this code to generalize this pattern.
		//We're basically converting a list with repeated objects
		//into a sorted set based on the number of appearances. (histogram)
		
		List<User> usersToRecommend = new ArrayList<User>();
		final Map<User,Integer> userInteractions = new HashMap<User,Integer>();
		
		for (Status status : statuses) {
			User user = status.getUser();
			if (userInteractions.containsKey(user)) {
				userInteractions.put(user, 1+userInteractions.get(user));
			} else if (!user.getScreenName().equalsIgnoreCase(SCREEN_NAME)) {
				userInteractions.put(user, 1);
				usersToRecommend.add(user);
			}
		}
		
		Collections.sort(usersToRecommend, new Comparator<User>() {
			@Override
			public int compare(User u1, User u2) {
				return userInteractions.get(u2) - userInteractions.get(u1);
			}
		});

		//Now start preparing the twitts with the users, recommend the best ones first.
		List<String> tweets = new ArrayList<String>();
		StringBuilder tempTweet = new StringBuilder("#FF");
		for (User user : usersToRecommend) {
			if ((tempTweet.length() + user.getScreenName().length() + " @".length()) <= MAX_TWEET_LENGTH) {
				tempTweet.append(" @" + user.getScreenName());
			} else {
				tweets.add(tempTweet.toString());
				tempTweet = new StringBuilder("#FF");
			}
		}

		//sometimes there's material for only one tweet.
		String possibleShortRecommendation = tempTweet.toString();
		if (!possibleShortRecommendation.equals("#FF")) {
			tweets.add(possibleShortRecommendation);
		}

		//Tweet away!
		for (String tweet : tweets) {
			try {
				twitter.updateStatus(tweet);
			} catch (TwitterException e) {
				e.printStackTrace();
			}
		}

	}

	public NLPTwitterToolbox(File propertyFile) {
		try {
			loadProperties(propertyFile);
			initTwitter();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		initFriends();
		initStatuses();
	}

	private void loadProperties(File propertyFile) throws Exception {
		String[] expectedPropertyKeys = new String[] { "debug",
				"oauth.consumerKey", "oauth.consumerSecret",
				"oauth.accessToken", "oauth.accessTokenSecret", "username" };

		props = new Properties();
		props.load(new FileInputStream(propertyFile));

		// make sure all expectedPropertyKeys are there.
		for (String key : expectedPropertyKeys) {
			if (!props.containsKey(key)) {
				throw new Exception("ERROR: Missing '" + key
						+ "' property on property file - "
						+ propertyFile.getAbsolutePath());
			}
		}
	}
	
	private void initStatuses() {
		fStatuses = new File(SCREEN_NAME + ".statuses.dat");
		loadStatuses();
	}

	/** This will load the statuses from disk */
	@SuppressWarnings("unchecked")
	private void loadStatuses() {
		if (!fStatuses.exists() || fStatuses.length() == 0) {
			STATUSES = new HashMap<Long, User>();
		} else {
			try {
				FileInputStream fis = new FileInputStream(fStatuses);
				ObjectInputStream ois = new ObjectInputStream(fis);
				STATUSES = (HashMap<Long,User>) ois.readObject();
			} catch (Exception e) {
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
		SCREEN_NAME = props.getProperty("username");
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

	/** Serializes the Twitter IDs of friends into a file. */
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
			IDs friendsIDs = twitter.getFriendsIDs(SCREEN_NAME,
					loadFriendCursor);
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
		Configuration configuration = new ConfigurationBuilder()
				.setOAuthConsumerKey(props.getProperty("oauth.consumerKey"))
				.setOAuthConsumerSecret(
						props.getProperty("oauth.consumerSecret"))
				.setOAuthAccessToken(props.getProperty("oauth.accessToken"))
				.setOAuthAccessTokenSecret(
						props.getProperty("oauth.accessTokenSecret")).build();

		twitter = new TwitterFactory(configuration).getInstance();
		twitter.verifyCredentials();
	}


	private void followNewUsers(List<Status> statuses) {
		for (Status status : statuses) {
			long userid;

			// don't follow your tail!
			if (status.getUser().getScreenName().equalsIgnoreCase(SCREEN_NAME)) {
				continue;
			}

			if (!FRIENDS.contains(userid = status.getUser().getId())) {
				try {
					System.out.println("Following @" + status.getUser().getScreenName());
					twitter.createFriendship(userid, true);
					FRIENDS.add(userid);
				} catch (TwitterException e) {
				}
			}
		}
		saveFriends();
	}

	private List<Status> getMentions() throws TwitterException {
		List<Status> statuses = twitter.getMentions();
		return statuses;
	}
}
