package com.nolapeles.twitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import twitter4j.IDs;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.User;

/**
 * This class follows back everybody who mentions you.
**/
public class NoPelesATusPanas {
	private static final String NLP_SCREEN_NAME = "ccarbone";
	private Twitter twitter;
	private Set<Long> FRIENDS;
	private long loadFriendCursor;
	private final File fFriends;

	public NoPelesATusPanas(){
		FRIENDS = new HashSet<Long>();
		fFriends = new File("friends.txt");
		initTwitter();
		initFriends();
	}
	
	private void initFriends() {
		loadFriends();
	}

	private void loadFriends() {
		if(!fFriends.exists() || fFriends.length()==0){
			getFriendsFromInternet();
			saveFriends();
		}
		else{	
			try {
				FileInputStream fis = new FileInputStream(fFriends);
				ObjectInputStream ois = new ObjectInputStream(fis);
				while(true){
					try{
						FRIENDS.add(ois.readLong());
					}
					catch (Exception e){
						fis.close();
						break;
					}
				}				
			} catch (Exception e) {
				return;
			}
		}		
	}

	private void saveFriends() {
		ObjectOutputStream oos = null;
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(fFriends);
			oos = new ObjectOutputStream(fos);
			for(Long lid : FRIENDS){
				oos.writeLong(lid);
			}
			
			try {
				oos.flush();
				oos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} catch (Exception e) {
			
		}
	}

	private void getFriendsFromInternet() {
		loadFriendCursor = -1;
		while(getNextFriendsIDs()){}
	}

	private boolean getNextFriendsIDs() {
		try {
			IDs friendsIDs = twitter.getFriendsIDs(NLP_SCREEN_NAME, loadFriendCursor);
			long[] iDs = friendsIDs.getIDs();
			for(long lid : iDs){
				FRIENDS.add(lid);
			}
			loadFriendCursor = friendsIDs.getNextCursor();
			return friendsIDs.hasNext();
		} catch (TwitterException e) {
			return false;
		}
	}

	public static void main(String[] args) {
		new NoPelesATusPanas().followBack();		
	}

	private void followBack() {
		try {
			List<Status> mentions = logInAndGetMentions(twitter);
			followNewUsers(mentions);
			saveFriends();
		} catch (TwitterException te) {
			te.printStackTrace();
			System.out.println("Failed to get timeline: " + te.getMessage());
			System.exit(-1);
		}
	}

	private void initTwitter() {
		twitter = new TwitterFactory().getInstance();
	}

	private void followNewUsers(List<Status> statuses) {
		for (Status status : statuses) {
			long userid;
			if (! FRIENDS.contains(userid = status.getUser().getId())){
				try {
					twitter.createFriendship(userid, true);
					FRIENDS.add(userid);
				} catch (TwitterException e) {
				}
			}
		}
	}

	private static List<Status> logInAndGetMentions(Twitter twitter)
			throws TwitterException {
		User user = twitter.verifyCredentials();
		List<Status> statuses = twitter.getMentions();
		System.out.println("Showing @" + user.getScreenName()
				+ "'s mentions.");
		return statuses;
	}
}
