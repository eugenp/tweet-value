package org.tweet.twitter.service;

import java.util.List;
import java.util.Set;

import org.common.service.LinkService;
import org.common.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.social.twitter.api.Entities;
import org.springframework.social.twitter.api.HashTagEntity;
import org.springframework.social.twitter.api.Tweet;
import org.springframework.social.twitter.api.TwitterProfile;
import org.springframework.stereotype.Service;
import org.tweet.twitter.util.HashtagWordFunction;
import org.tweet.twitter.util.TweetUtil;
import org.tweet.twitter.util.TwitterUtil;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * - local
 */
@Service
public class TweetService {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    LinkService linkService;

    public TweetService() {
        super();
    }

    // API

    // checks

    public final boolean isTweetWorthRetweetingByTextWithLink(final String tweetText) {
        return isTweetWorthRetweetingByTextWithLink(tweetText, TweetType.Standard);
    }

    /**
     * - <b>local</b> <br/>
     * Tweet is <b>not worth retweeting if</b>: <br/>
     * - doesn't contain any link <b>or</b> contains more than one single link<br/>
     * - is banned (single word, expression, regex)<br/>
     * - contains a link to a banned service (ex: instagram) <br/>
     * - is structurally valid<br/>
     * - <br/>
     */
    public final boolean isTweetWorthRetweetingByTextWithLink(final String tweetText, final TweetType tweetType) {
        if (!passesSetOfAdditionalChecksForTweeting(tweetText, tweetType)) {
            return false;
        }

        if (!isStructurallyValidForTweeting(tweetText)) {
            // was error for a while - validated - moving down to debug
            logger.debug("1 - Rejecting tweet because it is not structurally valid; tweet text= {}", tweetText);
            return false;
        }

        // is retweet check moved from here to isTweetWorthRetweetingByFullTweet
        return true;
    }

    /**
     * - <b>local</b> <br/>
     * Tweet is <b>not worth retweeting if</b>: <br/>
     * - doesn't contain any link <b>or</b> contains more than one single link <br/>
     * - is banned (single word, expression, regex)<br/>
     * - contains a link to a banned service (ex: instagram) <br/>
     * - is structurally valid (minimally) <br/>
     * - <b>note: does not check</b> if it passes level 0 and 1 checks <br/>
     */
    public final boolean passesSet3OfChecksForAnalysis(final String tweetText) {
        if (!containsLink(tweetText)) {
            return false;
        }
        if (TwitterUtil.isTweetBannedForAnalysis(tweetText)) {
            // debug should be OK
            logger.debug("Rejecting tweet because it is banned: \ntweet= {}", tweetText);
            return false;
        }

        if (linkService.extractUrls(tweetText).size() > 1) {
            // keep below error - there are a lot of tweets that fall into this category and it's not really relevant
            logger.debug("Rejecting tweet because it has more than one link; tweet text= {}", tweetText);
            return false;
        }

        if (!isStructurallyValidMinimal(tweetText)) {
            // newly changed - error for a while, then debug again
            logger.error("NEW - 2 - Rejecting tweet because it is not structurally valid; tweet text= {}", tweetText);
            return false;
        }

        // is retweet check moved from here to isTweetWorthRetweetingByFullTweet
        return true;
    }

    public final boolean passesSetOfAdditionalChecksForTweeting(final String tweetText, final TweetType tweetType) {
        if (!passesSet3OfChecksForAnalysis(tweetText)) {
            return false;
        }
        if (TwitterUtil.isTweetBannedForTweeting(tweetText, tweetType)) {
            // debug should be OK
            logger.debug("Rejecting tweet because it is banned: \ntweet= {}", tweetText);
            return false;
        }

        return true;
    }

    /**
     * - <b>local</b> <br/>
     * Passing bare minimal checks means: </br>
     * -  the author is not banned from being interacted with </br>
     * -  the tweet doesn't go over a max number of hashtags </br>
     */
    public final boolean passesSet1OfChecks(final Tweet tweet, final String hashtag) {
        final String hashTagInternal = (hashtag == null) ? "" : hashtag;

        if (TwitterUtil.isUserBannedFromRetweeting(tweet.getFromUser())) {
            logger.debug("tweet= {} on twitterTag= {} \nrejected because the original user is banned= {}", tweet, hashTagInternal, tweet.getFromUser());
            // debug temporary - should be trace
            return false;
        }

        final boolean shouldByNumberOfHashtags = isTweetWorthRetweetingByNumberOfHashtags(tweet);
        if (!shouldByNumberOfHashtags) {
            // was error - nothing really interesting, debug now, maybe trace later
            logger.debug("Rejected because the it contained to many hashtags - tweet= {} on twitterTag= {} ", TweetUtil.getText(tweet), hashTagInternal);
            return false;
        }

        return true;
    }

    /**
     * - <b>local</b> <br/>
     * Passing minimal checks means: </br>
     * - the tweet has a <b>language</b></br>
     * - the tweet has an accepted <b>language</b> </br>
     * - the author of the tweet has an accepted <b>language</b> </br>
     */
    public final boolean passesLanguageChecksForTweeting(final Tweet tweet, final String hashtag) {
        final String hashTagInternal = (hashtag == null) ? "" : hashtag;

        if (tweet.getLanguageCode() == null) {
            // temporary error
            logger.error("(for tweeting) - tweet= {}\n on twitterTag= {} \nrejected because it has the no language", TweetUtil.getText(tweet), hashTagInternal);
            return false;
        }

        // first check the language of the user
        if (tweet.getUser() == null || !TweetUtil.acceptedUserLangForTweeting.contains(tweet.getUser().getLanguage().trim())) {
            if (!TweetUtil.rejectedUserLangForTweeting.contains(tweet.getUser().getLanguage().trim())) {
                final String tweetUrl = "https://twitter.com/" + tweet.getFromUser() + "/status/" + tweet.getId();
                logger.info("(for tweeting) - tweet= {}\n on twitterTag= {} \nrejected because the user has USER language= {} \nfull tweet= {}", TweetUtil.getText(tweet), hashTagInternal, tweet.getUser().getLanguage(), tweetUrl);
                // was error - verified - moving down
            }
            return false;
        }

        // then check the language of the tweet
        if (!TweetUtil.acceptedTweetLangForTweeting.contains(tweet.getLanguageCode().trim())) {
            if (!TweetUtil.rejectedTweetLangForTweeting.contains(tweet.getLanguageCode().trim())) {
                logger.error("(for tweeting) - tweet= {}\n on twitterTag= {} \nrejected because it has the TWEET language= {}\n with user language= {}", TweetUtil.getText(tweet), hashTagInternal, tweet.getLanguageCode(), tweet.getUser().getLanguage().trim());
            }
            return false;
        }

        return true;
    }

    /**
     * - <b>local</b> <br/>
     * Passing minimal checks means: </br>
     * - the tweet has a <b>language</b></br>
     * - the tweet has an accepted <b>language</b> </br>
     * - the author of the tweet has an accepted <b>language</b> </br>
     */
    public final boolean passesLanguageChecksForAnalysis(final Tweet tweet, final String hashtagForLoggingRaw) {
        final String hashtagForLogging = (hashtagForLoggingRaw == null) ? "" : hashtagForLoggingRaw;

        // first - check the language of the user
        // - temp (13.10) - these will become Precondition.checkNotNull...
        if (tweet.getUser() == null) {
            logger.error("This should never happen - tweet has no user");
            return false;
        }
        if (tweet.getUser().getLanguage() == null) {
            logger.error("This should never happen - user for tweet has no language");
            return false;
        }

        final String tweetText = TweetUtil.getText(tweet);
        if (!passesUserLanguageChecksForAnalysis(tweet.getUser(), tweet, hashtagForLogging)) {
            return false;
        }

        // then check the language of the tweet
        if (tweet.getLanguageCode() == null) {
            // temporary error
            logger.error("(for analysis) - tweet= {}\n on twitterTag= {} \nrejected because it has the no language", tweetText, hashtagForLogging);
            return false;
        }

        if (!passesTweetLanguageChecksForAnalysis(tweet, hashtagForLogging)) {
            return false;
        }

        return true;
    }

    public final boolean passesUserLanguageChecksForAnalysis(final TwitterProfile user, final Tweet tweetForLogging, final String hashtagForLogging) {
        Preconditions.checkNotNull(user.getLanguage());
        Preconditions.checkState((tweetForLogging != null && hashtagForLogging != null) || (tweetForLogging == null && hashtagForLogging == null)); // these are both either null or not
        final boolean extensiveLogging = tweetForLogging != null;

        final String userLang = user.getLanguage().trim();
        if (TweetUtil.acceptedUserLangForAnalysis.contains(userLang)) {
            return true;
        }

        // not actively accepted
        // start - just logging stuff
        if (extensiveLogging) {
            if (!TweetUtil.rejectedUserLangForAnalysis.contains(userLang)) {
                final String tweetText = TweetUtil.getText(tweetForLogging);
                final String tweetUrl = "https://twitter.com/" + tweetForLogging.getUser() + "/status/" + tweetForLogging.getId();
                logger.error("(for analysis) - tweet= {}\n on twitterTag= {} \nrejected because the user has USER language= {} \nfull tweet= {}", tweetText, hashtagForLogging, tweetForLogging.getUser().getLanguage(), tweetUrl);
                // (26.10) - leaving it as it is for a bit longer, then moving it to warn - it did its job
                return false;
            }

            final String tweetText = TweetUtil.getText(tweetForLogging);
            final String tweetUrl = "https://twitter.com/" + tweetForLogging.getUser() + "/status/" + tweetForLogging.getId();
            logger.debug("(for analysis) - tweet= {}\n on twitterTag= {} \nrejected because the user has USER language= {} \nfull tweet= {}", tweetText, hashtagForLogging, tweetForLogging.getUser().getLanguage(), tweetUrl);
        } else {
            // these are validly rejected (I checked) - going down to debug
            logger.debug("(for analysis) - Should not interact with user= {} because user language is= {}", user.getScreenName(), userLang);
        }
        // done - just logging stuff

        return false;
    }

    public final boolean passesTweetLanguageChecksForAnalysis(final Tweet tweetForLogging, final String hashtagForLogging) {
        Preconditions.checkNotNull(tweetForLogging.getLanguageCode());

        final String tweetText = TweetUtil.getText(tweetForLogging);
        final String tweetLang = tweetForLogging.getLanguageCode().trim();
        final TwitterProfile user = tweetForLogging.getUser();

        if (TweetUtil.acceptedTweetLangForAnalysis.contains(tweetLang)) {
            return true;
        }

        // not actively accepted
        // is actively rejected?
        if (!TweetUtil.rejectedTweetLangForAnalysis.contains(tweetLang)) {
            // was error - looks OK right now - moving down
            final String tweetUrl = "https://twitter.com/" + user + "/status/" + tweetForLogging.getId();
            logger.info("(for analysis) - tweet= {}\n on twitterTag= {} \nrejected because it has the TWEET language= {}\n with USER language= {} \nfull tweet= {}", tweetText, hashtagForLogging, tweetLang, user.getLanguage().trim(), tweetUrl);
            return false;
        }

        // if not actively accepted or rejected - log and reject by default

        final String tweetUrl = "https://twitter.com/" + tweetForLogging.getUser() + "/status/" + tweetForLogging.getId();
        logger.debug("(for analysis) - tweet= {}\n on twitterTag= {} \nrejected because it has the TWEET language= {}\n with USER language= {} \nfull tweet= {}", tweetText, hashtagForLogging, tweetLang, user.getLanguage().trim(), tweetUrl);

        return false;
    }

    /**
     * Verifies that: <br/>
     * - the text has <b>no link</b> <br/>
     * - the text has the <b>correct length</b> <br/>
     */
    public final boolean isTweetTextValid(final String tweetTextNoUrl) {
        return TwitterUtil.isTweetTextWithoutLinkValid(tweetTextNoUrl);
    }

    /**
     * Verifies that: <br/>
     * - the text has the <b>correct length</b> <br/>
     */
    public final boolean isTweetFullValid(final String tweetTextWithUrl) {
        if (!TwitterUtil.isTweetTextWithLinkValid(tweetTextWithUrl)) {
            return false;
        }

        if (tweetTextWithUrl.matches(".*&\\S*;.*")) {
            // after cleanup, still contains unclean characters - fail validation
            logger.error("Probably unclean characters in: {}\n tweet url= ", tweetTextWithUrl);
            return false;
        }

        return true;
    }

    /**
     * - <b>local</b> <br/>
     * - verifies that a main url exists, and that the remaining text is longer then 11 chars
     */
    public final boolean isStructurallyValidForTweeting(final String tweetText) {
        if (!isStructurallyValidMinimal(tweetText)) {
            return false;
        }

        String processedTweet = tweetText;

        // remove mention
        processedTweet = processedTweet.replaceAll("(- )?via @\\w+", "");
        processedTweet = processedTweet.replaceAll("@\\w+", "");

        // remove hashtags
        processedTweet = processedTweet.replaceAll("#\\w+", "");

        // remove empty space
        processedTweet = processedTweet.replaceAll("  ", " ").trim();

        // then see what's left

        final Set<String> extractedUrls = linkService.extractUrls(processedTweet);
        final String mainUrl = linkService.determineMainUrl(extractedUrls);
        processedTweet = processedTweet.replace(mainUrl, "").trim();

        processedTweet = TextUtil.cleanupInvalidCharacters(processedTweet);

        if (processedTweet.length() <= 11) {
            return false;
        }

        final boolean isUpperCase = processedTweet.matches("[A-Z ->.]+");
        if (processedTweet.length() < 25 && isUpperCase) {
            logger.debug("Rejecting structurally invalid tweet: " + tweetText);
            return false;
        }

        return true;
    }

    /**
     * - <b>local</b> <br/>
     * - verifies that a main url exists
     */
    public final boolean isStructurallyValidMinimal(final String tweetText) {
        final Set<String> extractedUrls = linkService.extractUrls(tweetText);
        final String mainUrl = linkService.determineMainUrl(extractedUrls);
        if (mainUrl == null) {
            return false;
        }

        return true;
    }

    // processing

    /**
     * - cleans the invalid characters from text
     */
    public final String processPreValidity(final String text) {
        String resultAfterCleanup = TextUtil.cleanupInvalidCharacters(text);
        resultAfterCleanup = TextUtil.cleanupInvalidCharacters(resultAfterCleanup);
        resultAfterCleanup = TextUtil.trimTweet(resultAfterCleanup);

        return resultAfterCleanup;
    }

    /**
     * - <b>local</b> <br/>
     */
    final int countHashtags(final Tweet tweet) {
        return getHashtagsRaw(tweet).size();
    }

    // util

    /**
     * - local
     */
    final int getCharacterLenghtOfHashTags(final Tweet tweet) {
        int size = 0;
        for (final HashTagEntity hashTag : getHashtagsRaw(tweet)) {
            size += hashTag.getText().length();
            size += 2;
        }

        return size;
    }

    /**
     * - <b>local</b> <br/>
     * Tweet with more than 7 hashtags should not be retweeted
     */
    final boolean isTweetWorthRetweetingByNumberOfHashtags(final Tweet tweet) {
        final int countHashtags = countHashtags(tweet);
        if (countHashtags > 7) {
            // I have seen a lot of valid tweets with 7
            if (countHashtags == 7) {
                logger.error("Are there really valid tweets with 7 hashtags? Leave this running for a bit: " + TweetUtil.getText(tweet));
            }
            return false;
        }

        return true;
    }

    final String hashtagWordsFullTweet(final String fullTweet, final List<String> wordsToHash) {
        final Iterable<String> tokens = TwitterUtil.splitter.split(fullTweet);

        final HashtagWordFunction hashtagWordFunction = new HashtagWordFunction(wordsToHash);
        final Iterable<String> transformedTokens = Iterables.transform(tokens, hashtagWordFunction);

        final String processedTweet = TwitterUtil.joiner.join(transformedTokens);

        // check that hashtags + original tweet do not go over 141 chars
        if (fullTweet.length() + hashtagWordFunction.getTransformationsDone() > 141) {
            return fullTweet;
        }

        return processedTweet;
    }

    /**
     * - local <br>
     * - return - not null <br>
     */
    public final List<String> getHashtags(final Tweet tweet) {
        final List<HashTagEntity> hashtagsRaw = getHashtagsRaw(tweet);
        final List<String> hashtags = Lists.transform(hashtagsRaw, new Function<HashTagEntity, String>() {
            @Override
            public String apply(final HashTagEntity input) {
                return input.getText();
            }

        });

        return Preconditions.checkNotNull(hashtags);
    }

    final List<HashTagEntity> getHashtagsRaw(final Tweet tweet) {
        final Entities entities = tweet.getEntities();
        if (entities == null) {
            return Lists.newArrayList();
        }
        final List<HashTagEntity> hashTags = entities.getHashTags();
        if (hashTags == null) {
            return Lists.newArrayList();
        }

        return hashTags;
    }

    /**
     * - <b>local</b> <br/>
     * Determines if the tweet text contains a link
     */
    private final boolean containsLink(final String text) {
        return text.contains("http://") || text.contains("https://");
    }

}
