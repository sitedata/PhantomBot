/*
 * Copyright (C) 2016-2022 phantombot.github.io/PhantomBot
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tv.phantombot.cache;

import com.gmt2001.TwitchAPIv5;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;
import org.json.JSONObject;

public class UsernameCache {

    private static final UsernameCache instance = new UsernameCache();

    public static UsernameCache instance() {
        return instance;
    }

    private final Map<String, UserData> cache = new ConcurrentHashMap<>();
    private Instant timeoutExpire = Instant.now();
    private Instant lastFail = Instant.now();
    private int numfail = 0;

    private UsernameCache() {
        Thread.setDefaultUncaughtExceptionHandler(com.gmt2001.UncaughtExceptionHandler.instance());
    }

    private void lookupUserData(String username) {
        try {
            JSONObject user = TwitchAPIv5.instance().GetUser(username);

            if (user.getBoolean("_success")) {
                if (user.getInt("_http") == 200) {
                    if (user.getJSONArray("users").length() > 0) {
                        String displayName = user.getJSONArray("users").getJSONObject(0).getString("display_name").replaceAll("\\\\s", " ");
                        String userID = user.getJSONArray("users").getJSONObject(0).getString("_id");
                        cache.put(username, new UserData(displayName, userID));
                    }
                } else {
                    com.gmt2001.Console.debug.println("UsernameCache.updateCache: Failed to get username [" + username + "] http error [" + user.getInt("_http") + "]");
                }
            } else {
                if (user.getString("_exception").equalsIgnoreCase("SocketTimeoutException") || user.getString("_exception").equalsIgnoreCase("IOException")) {
                    if (lastFail.isAfter(Instant.now())) {
                        numfail++;
                    } else {
                        numfail = 1;
                    }

                    lastFail = Instant.now().plus(1, ChronoUnit.MINUTES);

                    if (numfail >= 5) {
                        timeoutExpire = Instant.now().plus(1, ChronoUnit.MINUTES);
                    }
                }
            }
        } catch (JSONException e) {
            com.gmt2001.Console.err.printStackTrace(e);
        }
    }

    // This will be implemented later
    // For now it's just to keep another class from throwing errors.
    public JSONObject getUserData(String username) throws JSONException {
        return new JSONObject("");
    }

    public String resolve(String username) {
        return resolve(username, new HashMap<>());
    }

    public String resolve(String username, Map<String, String> tags) {
        String lusername = username.toLowerCase();

        if (hasUser(lusername)) {
            return cache.get(lusername).getUserName();
        } else {
            if (username.equalsIgnoreCase("jtv") || username.equalsIgnoreCase("twitchnotify")) {
                return username;
            }

            if (tags.containsKey("display-name") && tags.get("display-name").equalsIgnoreCase(lusername) && tags.containsKey("user-id")) {
                cache.put(lusername, new UserData(tags.get("display-name"), tags.get("user-id")));
                return tags.get("display-name");
            }

            /* While the user-id should always be present, this is just a stop-gap measure. */
            if (tags.containsKey("display-name") && tags.get("display-name").equalsIgnoreCase(lusername)) {
                return tags.get("display-name");
            }

            if (Instant.now().isBefore(timeoutExpire)) {
                return username;
            }

            lookupUserData(lusername);
            if (hasUser(lusername)) {
                return cache.get(lusername).getUserName();
            } else {
                return lusername;
            }
        }
    }

    public boolean exists(String userName) {
        // Check the cache first, if the user doesn't exist call the API and check the cache again.
        if (cache.containsKey(userName)) {
            return true;
        } else {
            lookupUserData(userName);

            return cache.containsKey(userName);
        }
    }

    public void addUser(String userName, String displayName, int userID) {
        if (!hasUser(userName) && displayName.length() > 0) {
            cache.put(userName, new UserData(displayName.replaceAll("\\\\s", " "), userID));
        }
    }

    public void addUser(String userName, String displayName, String userID) {
        if (!hasUser(userName) && displayName.length() > 0 && userID.length() > 0) {
            cache.put(userName, new UserData(displayName.replaceAll("\\\\s", " "), userID));
        }
    }

    public boolean hasUser(String userName) {
        return cache.containsKey(userName);
    }

    public String get(String userName) {
        return (hasUser(userName) ? cache.get(userName).getUserName() : userName);
    }

    public String getID(String userName) {
        return this.getID(userName, false);
    }

    public String getID(String userName, boolean forceIfMissing) {
        String lusername = userName.toLowerCase();
        if (hasUser(lusername)) {
            return cache.get(lusername).getUserID();
        } else {
            if (Instant.now().isBefore(timeoutExpire) && !forceIfMissing) {
                return "0";
            }
            lookupUserData(lusername);
            if (hasUser(lusername)) {
                return cache.get(lusername).getUserID();
            }
        }
        return "0";
    }

    public void removeUser(String userName) {
        userName = userName.toLowerCase();

        if (hasUser(userName)) {
            cache.remove(userName);
        }
    }

    /*
     * Internal object for tracking user data.
     * Note that while Twitch represents the userID as a String, it is an integer value.  We
     * define this as an int here to conserve memory usage.  The maximum value of an unsigned
     * int within Java is 4,294,967,295 which should serve as a large enough data type.
     */
    private class UserData {

        private String userName;
        private int userID;

        public UserData(String userName, int userID) {
            this.userName = userName;
            this.userID = userID;
        }

        public UserData(String userName, String userID) {
            this.userName = userName;
            this.userID = Integer.parseUnsignedInt(userID);
        }

        public void putUserName(String userName) {
            this.userName = userName;
        }

        public void putUserID(int userID) {
            this.userID = userID;
        }

        public void putUserID(String userID) {
            this.userID = Integer.parseUnsignedInt(userID);
        }

        public String getUserName() {
            return userName;
        }

        public String getUserID() {
            return Integer.toUnsignedString(userID);
        }
    }
}
