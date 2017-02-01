/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tech.rithm.udacitysunwearable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunFace.Engine> mWeakReference;

        public EngineHandler(SunFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener{

        // -->
        private GoogleApiClient googleApiClient;
        private static final String PATH_WEATHER = "/weather";
        private static final String PATH_WEATHER_REQUEST = "/weather-request";
        private static final String KEY_MAX_TEMP = "com.sunshine.key.max_temp";
        private static final String KEY_MIN_TEMP = "com.sunshine.key.min_temp";
        private static final String KEY_TIME_STAMP = "com.sunshine.key.stamp";
        private static final String KEY_ASSET_IMAGE = "com.sunshine.key.asset_image";
        private static final String KEY_WEATHER_DESC = "com.sunshine.key.weather_desc";
        private String maxTemp = "99";
        private String minTemp = "11";
        private long time_stamp;
        private String weatherDescription = "..awaiting";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint datePaint;
        Paint mMaxPaint;
        Paint mMinPaint;
        Paint line_paint;
        Paint mWeatherDescription;
        Paint mIcon;
        Bitmap icon;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;


        boolean isRound;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            googleApiClient = new GoogleApiClient.Builder(SunFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset_square);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.colorPrimary));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTextPaint.setTextAlign(Paint.Align.CENTER);

            datePaint = new Paint();
            datePaint.setColor(Color.WHITE);
            datePaint.setTextSize(resources.getDimension(R.dimen.digital_date_size));
            datePaint.setAntiAlias(true);

            mMaxPaint = new Paint();
            mMaxPaint.setColor(resources.getColor(R.color.detail_accent_pane_background));
            mMaxPaint.setTextSize(resources.getDimension(R.dimen.high_temp_text_size));
            mMaxPaint.setAntiAlias(true);

            mMinPaint = new Paint();
            mMinPaint.setColor(resources.getColor(R.color.detail_accent_label));
            mMinPaint.setTextSize(resources.getDimension(R.dimen.low_temp_text_size));
            mMinPaint.setAntiAlias(true);

            line_paint = new Paint();
            line_paint.setColor(Color.LTGRAY);
            line_paint.setAntiAlias(true);

            mWeatherDescription = new Paint();
            mWeatherDescription.setColor(Color.LTGRAY);
            mWeatherDescription.setAntiAlias(true);
            mWeatherDescription.setTextSize(18);

            mIcon = new Paint();

            mCalendar = Calendar.getInstance();


        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                googleApiClient.connect();
                sendMessage(PATH_WEATHER_REQUEST, "RequestWeather".getBytes());
                registerReceiver();
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                Wearable.DataApi.removeListener(googleApiClient, this);
                googleApiClient.disconnect();
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunFace.this.getResources();
            isRound = insets.isRound();
            mYOffset = resources.getDimension(isRound ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset_square);
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            int watch_width = bounds.width();
            int watch_height = bounds.height();
            int center_x = watch_width / 2;
            int center_y = watch_height / 2;

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String textTime = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
            canvas.drawText(textTime, watch_width / 2, mYOffset, mTextPaint);

            SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy");
            sdf.setTimeZone(mCalendar.getTimeZone());
            String day = sdf.format(mCalendar.getTime());
            float dateXOffset = computeXOffset(day, datePaint, bounds);
            float dateYOffset = computeDateYOffset(day, datePaint);
            canvas.drawText(day, dateXOffset, dateYOffset + mYOffset, datePaint);

            float line_Yoffset = getResources().getDimension(isRound ? R.dimen.digital_y_line_offset_round
                                                                    : R.dimen.digital_y_line_offset_square);
            float line_length = getResources().getDimension(isRound ? R.dimen.line_length_round
                                                                    : R.dimen.line_length_square);

            canvas.drawLine(center_x - line_length, center_y + line_Yoffset , center_x + line_length, center_y  + line_Yoffset, line_paint);

            float max_temp_x_offset = getResources().getDimension(isRound ? R.dimen.high_temp_x_offset_round : R.dimen.high_temp_x_offset_square);
            float max_temp_y_offset = getResources().getDimension(isRound ? R.dimen.high_temp_y_offset_round : R.dimen.high_temp_y_offset_square);

            float min_temp_x_offset = getResources().getDimension(isRound ? R.dimen.low_temp_x_offset_round : R.dimen.low_temp_x_offset_square);
            float min_temp_y_offset = getResources().getDimension(isRound ? R.dimen.low_temp_y_offset_round : R.dimen.low_temp_y_offset_square);

            canvas.drawText(maxTemp, center_x + max_temp_x_offset, center_y + max_temp_y_offset, mMaxPaint);
            canvas.drawText(minTemp, center_x + min_temp_x_offset, center_y + min_temp_y_offset, mMinPaint);

            float bitmap_x_offset = getResources().getDimension(isRound ? R.dimen.bitmap_x_offset_round : R.dimen.bitmap_x_offset_square);
            float bitmap_y_offset = getResources().getDimension(isRound ? R.dimen.bitmap_y_offset_round : R.dimen.bitmap_y_offset_square);
            if(icon != null){
                canvas.drawBitmap(icon,center_x - bitmap_x_offset, center_y + bitmap_y_offset, mIcon );
            }

            float desc_x_offset = getResources().getDimension(isRound ? R.dimen.desc_x_offset_round : R.dimen.desc_x_offset_square);
            float desc_y_offset = getResources().getDimension(isRound ? R.dimen.desc_y_offset_round : R.dimen.desc_y_offset_square);
            canvas.drawText(weatherDescription, center_x - desc_x_offset, center_y + desc_y_offset, mWeatherDescription);


        }

        private float computeXOffset(String text, Paint paint, Rect watchBounds) {
            float centerX = watchBounds.exactCenterX();
            float timeLength = paint.measureText(text);
            return centerX - (timeLength / 2.0f);
        }

        private float computeTimeYOffset(String timeText, Paint timePaint, Rect watchBounds) {
            float centerY = watchBounds.exactCenterY();
            Rect textBounds = new Rect();
            timePaint.getTextBounds(timeText, 0, timeText.length(), textBounds);
            int textHeight = textBounds.height();
            return centerY + (textHeight / 2.0f);
        }

        private float computeDateYOffset(String dateText, Paint datePaint) {
            Rect textBounds = new Rect();
            datePaint.getTextBounds(dateText, 0, dateText.length(), textBounds);
            return textBounds.height() + 10.0f;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle){

            Wearable.DataApi.addListener(googleApiClient, this);
            sendMessage(PATH_WEATHER_REQUEST, "CounterRequest".getBytes());
        }

        private void sendMessage(final String path, final byte[] data){
            Log.i("message", "request for data info");
            Wearable.NodeApi.getConnectedNodes(googleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                @Override
                public void onResult(@NonNull NodeApi.GetConnectedNodesResult getConnectedNodesResult) {

                    for(Node node :getConnectedNodesResult.getNodes()){
                        Wearable.MessageApi.sendMessage(googleApiClient, node.getId(), path, data);
                    }
                }
            });
        }

        @Override
        public void onConnectionSuspended(int cause){

        }

        @Override
        public void onConnectionFailed(ConnectionResult result){

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents){

            for (DataEvent event : dataEvents){
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(PATH_WEATHER) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        //updateCount(dataMap.getInt(COUNT_KEY));
                        updateMaxTemp(dataMap.getString(KEY_MAX_TEMP));
                        updateMinTemp(dataMap.getString(KEY_MIN_TEMP));
                        updateTimeStamp(dataMap.getLong(KEY_TIME_STAMP));
                        updateWeatherDesc(dataMap.getString(KEY_WEATHER_DESC));
                        loadBitmapFromAsset(dataMap.getAsset(KEY_ASSET_IMAGE));
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED){
                    // DataIteme deleted
                }
            }

            invalidate();


        }

        private void loadBitmapFromAsset(final Asset asset){
            if (asset == null){
                throw new IllegalArgumentException("asset must be non null");
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    ConnectionResult connectionResult = googleApiClient.blockingConnect(15000, TimeUnit.MILLISECONDS);
                    if(connectionResult.isSuccess()){
                        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(googleApiClient, asset).await().getInputStream();
                        googleApiClient.disconnect();

                        if(assetInputStream != null){
                            icon = BitmapFactory.decodeStream(assetInputStream);
                        }
                    }
                }
            }).start();
        }

        private void updateMaxTemp(String temp){
            maxTemp = temp;
        }

        private void updateMinTemp(String temp){
            minTemp = temp;
        }

        private void updateTimeStamp(long timeStamp) {
            this.time_stamp = timeStamp;
        }


        private void updateWeatherDesc(String weatherDescription){
            this.weatherDescription = weatherDescription;
        }
    }
}
