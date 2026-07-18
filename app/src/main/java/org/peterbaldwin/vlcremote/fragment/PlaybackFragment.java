/*-
 *  Copyright (C) 2011 Peter Baldwin   
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.peterbaldwin.vlcremote.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.falcofemoralis.hdrezkaapp.utils.RezkaPlayback;
import org.peterbaldwin.vlcremote.youtube.YoutubePlayback;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;
import org.peterbaldwin.client.android.vlcremote.R;
import org.peterbaldwin.vlcremote.compat.Util;
import org.peterbaldwin.vlcremote.intent.Intents;
import org.peterbaldwin.vlcremote.model.Preferences;
import org.peterbaldwin.vlcremote.model.Status;
import org.peterbaldwin.vlcremote.net.MediaServer.StatusRequest;
import org.peterbaldwin.vlcremote.net.MediaServer.StatusRequest.CommandInterface;
import org.peterbaldwin.vlcremote.net.MediaServer.StatusRequest.CommandInterface.PlaybackInterface;

/**
 * Controls playback and displays progress.
 */
public class PlaybackFragment extends MediaFragment implements View.OnClickListener,
        View.OnLongClickListener, OnSeekBarChangeListener {

    private BroadcastReceiver mStatusReceiver;

    private ImageButton mButtonPlaylistPause;

    private ImageButton mButtonPlaylistStop;

    private ImageButton mButtonPlaylistSkipForward;

    private ImageButton mButtonPlaylistSkipBackward;

    private ImageButton mButtonPlaylistSeekForward;

    private ImageButton mButtonPlaylistSeekBackward;

    private ImageButton mButtonPlaylistChapterNext;

    private ImageButton mButtonPlaylistChapterPrevious;

    private SeekBar mSeekPosition;

    private TextView mTextTime;

    private TextView mTextLength;

    private TextView mDownloadStatus;

    // Autorun (auto-advance HDrezka series): remember the last playing position so a
    // natural end (state -> stopped near the media length) can be told from a manual stop.
    private int mAutorunPrevTime;
    private int mAutorunPrevLength;
    private boolean mAutorunFiredForThisEnd;

    // True while the user is dragging the position seekbar, so we don't flood VLC with a
    // seek command per pixel (which hangs network streams) and don't fight the thumb with
    // status updates. A single seek is issued when the drag ends.
    private boolean mTrackingPosition;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.audio_player_common, container, false);
        mButtonPlaylistPause = setupImageButton(v, R.id.button_pause);
        mButtonPlaylistStop = setupImageButton(v, R.id.button_stop);
        mButtonPlaylistSkipForward = setupImageButton(v, R.id.button_skip_forward);
        mButtonPlaylistSkipBackward = setupImageButton(v, R.id.button_skip_backward);
        mButtonPlaylistSeekForward = setupImageButton(v, R.id.button_seek_forward);
        mButtonPlaylistSeekBackward = setupImageButton(v, R.id.button_seek_backward);
        mButtonPlaylistChapterNext = setupImageButton(v, R.id.button_chapter_next);
        mButtonPlaylistChapterPrevious = setupImageButton(v, R.id.button_chapter_previous);

        mSeekPosition = (SeekBar) v.findViewById(R.id.seek_progress);
        mSeekPosition.setMax(100);
        mSeekPosition.setOnSeekBarChangeListener(this);

        mTextTime = (TextView) v.findViewById(R.id.text_time);
        mTextLength = (TextView) v.findViewById(R.id.text_length);
        mDownloadStatus = (TextView) v.findViewById(R.id.youtube_download_status);
        return v;
    }

    private ImageButton setupImageButton(View v, int viewId) {
        ImageButton button = (ImageButton) v.findViewById(viewId);
        if (button != null) {
            button.setOnClickListener(this);
            button.setOnLongClickListener(this);
        }
        return button;
    }

    private StatusRequest status() {
        return getMediaServer().status();
    }

    private CommandInterface command() {
        return status().command;
    }

    private PlaybackInterface playlist() {
        return command().playback;
    }

    /** {@inheritDoc} */
    public void onClick(View v) {
        if (v == mButtonPlaylistPause) {
            playlist().pause();
        } else if (v == mButtonPlaylistStop) {
            playlist().stop();
        } else if (v == mButtonPlaylistSkipBackward) {
            // For an HDrezka series, step to the previous episode (crossing seasons); at
            // the very first episode show a toast and do nothing. Otherwise fall back to
            // normal VLC playlist navigation.
            if (RezkaPlayback.isActiveSeries()) {
                if (!RezkaPlayback.playPrevious(getActivity())) {
                    Toast.makeText(getActivity(), R.string.rezka_no_prev_episode, Toast.LENGTH_SHORT).show();
                }
            } else if (YoutubePlayback.isActive()) {
                if (!YoutubePlayback.playPrevious(getActivity())) {
                    Toast.makeText(getActivity(), R.string.youtube_no_prev, Toast.LENGTH_SHORT).show();
                }
            } else {
                playlist().previous();
            }
        } else if (v == mButtonPlaylistSkipForward) {
            if (RezkaPlayback.isActiveSeries()) {
                if (!RezkaPlayback.playNext(getActivity())) {
                    Toast.makeText(getActivity(), R.string.rezka_no_next_episode, Toast.LENGTH_SHORT).show();
                }
            } else if (YoutubePlayback.isActive()) {
                if (!YoutubePlayback.playNext(getActivity())) {
                    Toast.makeText(getActivity(), R.string.youtube_no_next, Toast.LENGTH_SHORT).show();
                }
            } else {
                playlist().next();
            }
        } else if (v == mButtonPlaylistSeekBackward) {
            command().seek(Uri.encode("-".concat(Preferences.get(getActivity()).getSeekTime())));
        } else if (v == mButtonPlaylistSeekForward) {
            command().seek(Uri.encode("+".concat(Preferences.get(getActivity()).getSeekTime())));
        } else if (v == mButtonPlaylistChapterPrevious) {
            command().key("chapter-prev");
        } else if (v == mButtonPlaylistChapterNext) {
            command().key("chapter-next");
        }
    }

    public boolean onLongClick(View v) {
        Toast.makeText(v.getContext(), v.getContentDescription(), Toast.LENGTH_SHORT).show();
        return true;
    }

    /** {@inheritDoc} */
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == mSeekPosition) {
            if (fromUser) {
                // Only preview the target time while dragging; the actual seek is issued
                // once, on release, to avoid overwhelming VLC with per-pixel seeks.
                mTextTime.setText(formatTime(progress));
            }
        }
    }

    /** {@inheritDoc} */
    public void onStartTrackingTouch(SeekBar seekBar) {
        if (seekBar == mSeekPosition) {
            mTrackingPosition = true;
        }
    }

    /** {@inheritDoc} */
    public void onStopTrackingTouch(SeekBar seekBar) {
        mTrackingPosition = false;
        if (seekBar == mSeekPosition) {
            seekPosition();
        }
    }

    private void seekPosition() {
        int position = mSeekPosition.getProgress();
        command().seek(String.valueOf(position));
    }

    @Override
    public void onResume() {
        super.onResume();
        mStatusReceiver = new StatusReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intents.ACTION_STATUS);
        Util.registerReceiver(getActivity(), mStatusReceiver, filter);
    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(mStatusReceiver);
        mStatusReceiver = null;
        super.onPause();
    }

    void onStatusChanged(Status status) {
        handleAutorun(status);

        // After an app restart, reconnect to a still-running download for the file VLC is
        // currently playing (mux_<id>.mkv) so the progress indicator + polling resume.
        if (!org.peterbaldwin.vlcremote.youtube.YtDownloadManager.isActive() && status.getTrack() != null) {
            String fn = status.getTrack().getName();
            if (fn != null && fn.startsWith("mux_") && fn.endsWith(".mp4")) {
                Preferences prefs = Preferences.get(getActivity());
                String authority = prefs == null ? null : prefs.getAuthority();
                if (authority != null) {
                    org.peterbaldwin.vlcremote.youtube.YtDownloadManager.resume(getActivity(), fn, authority);
                }
            }
        }

        // YouTube "Best" download progress / completion indicator (only if enabled in settings).
        if (mDownloadStatus != null) {
            boolean showDl = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(getActivity())
                    .getBoolean("youtube_download_status", true);
            String dl = showDl ? org.peterbaldwin.vlcremote.youtube.YtDownloadManager.statusText() : null;
            if (dl == null) {
                mDownloadStatus.setVisibility(View.GONE);
            } else {
                mDownloadStatus.setText(dl);
                mDownloadStatus.setVisibility(View.VISIBLE);
            }
        }

        int resId = status.isPlaying() ? R.drawable.ic_media_playback_pause
                : R.drawable.ic_media_playback_start;
        mButtonPlaylistPause.setImageResource(resId);

        int time = status.getTime();
        int length = status.getLength();
        // During a YouTube download the file (and VLC's reported length) only covers what has
        // been downloaded; show the real full duration from NewPipe instead.
        long ytDuration = org.peterbaldwin.vlcremote.youtube.YtDownloadManager.totalDurationSec();
        if (ytDuration > 0) {
            length = (int) ytDuration;
        }
        mSeekPosition.setMax(length);

        // Show the downloaded region as the seekbar's buffered (secondary) fill for a YouTube
        // download; keep it clear for rezka / plain file playback.
        if (ytDuration > 0) {
            mSeekPosition.setSecondaryProgress((int) org.peterbaldwin.vlcremote.youtube.YtDownloadManager.downloadedSec());
        } else {
            mSeekPosition.setSecondaryProgress(0);
        }

        // Call setKeyProgressIncrement after calling setMax because the
        // implementation of setMax will automatically adjust the increment.
        mSeekPosition.setKeyProgressIncrement(3);

        String formattedLength = formatTime(length);
        mTextLength.setText(formattedLength);

        // While the user is dragging, leave the thumb and time under their control.
        if (!mTrackingPosition) {
            mSeekPosition.setProgress(time);
            mTextTime.setText(formatTime(time));
        }
    }

    /**
     * When "autorun" is on and an HDrezka series is playing, advance to the next episode
     * once the current one finishes on its own (VLC stops with the position at the end).
     */
    private void handleAutorun(Status status) {
        boolean autorun = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(getActivity()).getBoolean(ButtonsFragment.KEY_AUTORUN, false);
        if (status.isStopped()) {
            if (autorun && !mAutorunFiredForThisEnd
                    && mAutorunPrevLength > 0 && mAutorunPrevTime >= mAutorunPrevLength - 5) {
                if (RezkaPlayback.isActiveSeries()) {
                    mAutorunFiredForThisEnd = true;
                    RezkaPlayback.playNext(getActivity());
                } else if (YoutubePlayback.isActive()) {
                    mAutorunFiredForThisEnd = true;
                    YoutubePlayback.playNext(getActivity());
                }
            }
        } else {
            mAutorunFiredForThisEnd = false;
            if (status.getLength() > 0) {
                mAutorunPrevTime = status.getTime();
                mAutorunPrevLength = status.getLength();
            }
        }
    }

    private static void doubleDigit(StringBuilder builder, long value) {
        builder.insert(0, value);
        if (value < 10) {
            builder.insert(0, '0');
        }
    }

    /**
     * Formats a time.
     * 
     * @param time the time (in seconds)
     * @return the formatted time.
     */
    private static String formatTime(int time) {
        long seconds = time % 60;
        time /= 60;
        long minutes = time % 60;
        time /= 60;
        long hours = time;
        StringBuilder builder = new StringBuilder(8);
        doubleDigit(builder, seconds);
        builder.insert(0, ':');
        if (hours == 0) {
            builder.insert(0, minutes);
        } else {
            doubleDigit(builder, minutes);
            builder.insert(0, ':');
            builder.insert(0, hours);
        }
        return builder.toString();
    }

    private class StatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intents.ACTION_STATUS.equals(action)) {
                Status status = (Status) intent.getSerializableExtra(Intents.EXTRA_STATUS);
                onStatusChanged(status);
            }
        }
    }
}
