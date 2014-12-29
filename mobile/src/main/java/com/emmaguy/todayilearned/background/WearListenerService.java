package com.emmaguy.todayilearned.background;

import android.content.Intent;
import android.net.Uri;
import android.preference.PreferenceManager;

import com.commonsware.cwac.wakeful.WakefulIntentService;
import com.emmaguy.todayilearned.Logger;
import com.emmaguy.todayilearned.PocketUtil;
import com.emmaguy.todayilearned.R;
import com.emmaguy.todayilearned.data.Reddit;
import com.emmaguy.todayilearned.data.RedditRequestInterceptor;
import com.emmaguy.todayilearned.data.response.CommentResponse;
import com.emmaguy.todayilearned.data.response.MarkAllReadResponse;
import com.emmaguy.todayilearned.sharedlib.Constants;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.gson.GsonBuilder;

import java.util.List;

import retrofit.RestAdapter;
import retrofit.converter.GsonConverter;
import rx.Observer;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

public class WearListenerService extends WearableListenerService {
    public static final String REDDIT_URL = "http://www.reddit.com";
    private GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (messageEvent.getPath().equals(Constants.PATH_REFRESH)) {
            WakefulIntentService.sendWakefulWork(this, RetrieveService.class);
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
        dataEvents.close();

        for (DataEvent event : events) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

                String path = event.getDataItem().getUri().getPath();
                Logger.Log("Path: " + path);

                if (Constants.PATH_REPLY.equals(path)) {
                    String fullname = dataMap.getString(Constants.PATH_KEY_POST_FULLNAME);
                    String message = dataMap.getString(Constants.PATH_KEY_MESSAGE);
                    boolean isDirectMessage = dataMap.getBoolean(Constants.PATH_KEY_IS_DIRECT_MESSAGE);

                    if (isDirectMessage) {
                        String subject = dataMap.getString(Constants.PATH_KEY_MESSAGE_SUBJECT);
                        String toUser = dataMap.getString(Constants.PATH_KEY_MESSAGE_TO_USER);

                        replyToDirectMessage(subject, message, toUser);
                    } else {
                        replyToRedditPost(fullname, message);
                    }
                } else if (Constants.PATH_OPEN_ON_PHONE.equals(path)) {
                    String permalink = dataMap.getString(Constants.KEY_POST_PERMALINK);
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(REDDIT_URL + permalink));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } else if (Constants.PATH_SAVE_TO_POCKET.equals(path)) {
                    String permalink = dataMap.getString(Constants.KEY_POST_PERMALINK);
                    String url = REDDIT_URL + permalink;

                    Intent intent = PocketUtil.newAddToPocketIntent(url, "", this);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (intent == null) {
                        Logger.sendEvent(getApplicationContext(), Logger.LOG_EVENT_SAVE_TO_POCKET, Logger.LOG_EVENT_FAILURE);
                        sendReplyResult(Constants.PATH_KEY_SAVE_TO_POCKET_RESULT_FAILED);
                    } else {
                        startActivity(intent);
                        Logger.sendEvent(getApplicationContext(), Logger.LOG_EVENT_SAVE_TO_POCKET, Logger.LOG_EVENT_SUCCESS);
                        sendReplyResult(Constants.PATH_KEY_SAVE_TO_POCKET_RESULT_SUCCESS);
                    }
                } else if (Constants.PATH_VOTE.equals(path)) {
                    String fullname = dataMap.getString(Constants.PATH_KEY_POST_FULLNAME);
                    int voteDirection = dataMap.getInt(Constants.KEY_POST_VOTE_DIRECTION);
                    vote(fullname, voteDirection);
                }
            }
        }
    }

    private void vote(String fullname, final int voteDirection) {
        RestAdapter restAdapter = new RestAdapter.Builder()
                .setEndpoint(Constants.ENDPOINT_URL_REDDIT)
                .setRequestInterceptor(new RedditRequestInterceptor(getCookie(), getModhash()))
                .build();

        final Reddit redditEndpoint = restAdapter.create(Reddit.class);
        redditEndpoint.vote(fullname, voteDirection)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Action1<Void>() {
                    @Override
                    public void call(Void v) {

                    }
                }, new Action1<Throwable>() {
                    @Override
                    public void call(Throwable e) {
                        Logger.sendThrowable(getApplicationContext(), "Failed to vote", e);
                        sendReplyResult(Constants.PATH_KEY_VOTE_RESULT_FAILED);
                    }
                }, new Action0() {
                    @Override
                    public void call() {
                        sendReplyResult(Constants.PATH_KEY_VOTE_RESULT_SUCCESS);
                    }
                });
    }

    private void replyToDirectMessage(String subject, String message, String toUser) {
        final Reddit redditEndpoint = getRestAdapter().create(Reddit.class);
        redditEndpoint.replyToDirectMessage(subject, message, toUser)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<CommentResponse>() {
                    @Override
                    public void onNext(CommentResponse response) {
                        if (response.hasErrors()) {
                            throw new RuntimeException("Failed to reply to DM: " + response);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        Logger.sendEvent(getApplicationContext(), Logger.LOG_EVENT_SEND_DM, Logger.LOG_EVENT_SUCCESS);
                        sendReplyResult(Constants.PATH_POST_REPLY_RESULT_SUCCESS);
                    }

                    @Override
                    public void onError(Throwable e) {
                        Logger.sendEvent(getApplicationContext(), Logger.LOG_EVENT_SEND_DM, Logger.LOG_EVENT_FAILURE);
                        Logger.sendThrowable(getApplicationContext(), e.getMessage(), e);
                        sendReplyResult(Constants.PATH_POST_REPLY_RESULT_FAILURE);
                    }
                });
    }

    private void replyToRedditPost(String fullname, String message) {
        final Reddit redditEndpoint = getRestAdapter().create(Reddit.class);
        redditEndpoint.commentOnPost(message, fullname)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<CommentResponse>() {
                    @Override
                    public void onNext(CommentResponse response) {
                        if (response.hasErrors()) {
                            throw new RuntimeException("Failed to comment on post: " + response);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        sendReplyResult(Constants.PATH_POST_REPLY_RESULT_SUCCESS);
                        Logger.sendEvent(getApplicationContext(), Logger.LOG_EVENT_REPLY_TO_POST, Logger.LOG_EVENT_SUCCESS);
                    }

                    @Override
                    public void onError(Throwable e) {
                        Logger.sendEvent(getApplicationContext(), Logger.LOG_EVENT_REPLY_TO_POST, Logger.LOG_EVENT_FAILURE);
                        Logger.sendThrowable(getApplicationContext(), e.getMessage(), e);
                        sendReplyResult(Constants.PATH_POST_REPLY_RESULT_FAILURE);
                    }
                });
    }

    private RestAdapter getRestAdapter() {
        return new RestAdapter.Builder()
                .setEndpoint(Constants.ENDPOINT_URL_REDDIT)
                .setRequestInterceptor(new RedditRequestInterceptor(getCookie(), getModhash()))
                .setConverter(new GsonConverter(new GsonBuilder().registerTypeAdapter(CommentResponse.class, new CommentResponse.CommentResponseJsonDeserializer()).create()))
                .build();
    }


    private String getModhash() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.prefs_key_modhash), "");
    }

    private String getCookie() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.prefs_key_cookie), "");
    }

    private void sendReplyResult(final String result) {
        Wearable.MessageApi.sendMessage(mGoogleApiClient, "", result, null).setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
            @Override
            public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                Logger.Log("sendReplyResult: " + result + " status " + sendMessageResult.getStatus());
            }
        });
    }
}