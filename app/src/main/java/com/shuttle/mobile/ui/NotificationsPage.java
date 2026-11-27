package com.shuttle.mobile.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.shuttle.common.ShiftTime;
import com.shuttle.mobile.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

/** Shared notification inbox for both signed-in roles. */
final class NotificationsPage {
    private static final String PATH = "/notifications";
    private static final long REFRESH_MS = 10_000;

    private record Notice(String id, String title, String message, long createdAt, boolean read) {
        Notice markedRead() {
            return new Notice(id, title, message, createdAt, true);
        }

        int icon() {
            if (id.startsWith("vehicle-")) return R.drawable.ic_vehicle;
            if (id.startsWith("shift-")) return R.drawable.ic_nav_schedule;
            return R.drawable.ic_bell;
        }
    }

    private final AccountActivity activity;
    private final String token = Session.getToken();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            load();
            if (resumed) handler.postDelayed(this, REFRESH_MS);
        }
    };
    private final TextView all, unread, markAll, count, status, description;
    private final View placeholder;
    private final LinearLayout cards;
    private List<Notice> notices = new ArrayList<>();
    private boolean unreadOnly, resumed, loading, reading, loaded;
    private int requestVersion;
    private String failure;
    private String renderedDay;

    NotificationsPage(AccountActivity activity) {
        this.activity = activity;
        all = activity.findViewById(R.id.btnNotificationsAll);
        unread = activity.findViewById(R.id.btnNotificationsUnread);
        markAll = activity.findViewById(R.id.btnNotificationsReadAll);
        count = activity.findViewById(R.id.tvNotificationsUnreadCount);
        status = activity.findViewById(R.id.tvNotificationsEmpty);
        description = activity.findViewById(R.id.tvNotificationsDescription);
        placeholder = activity.findViewById(R.id.notificationsPlaceholder);
        cards = activity.findViewById(R.id.notificationCards);
        all.setOnClickListener(v -> filter(false));
        unread.setOnClickListener(v -> filter(true));
        markAll.setOnClickListener(v -> markAllRead());
        filter(false);
    }

    void resume() {
        resumed = true;
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(refresh);
    }

    private void filter(boolean unreadOnly) {
        this.unreadOnly = unreadOnly;
        all.setSelected(!unreadOnly);
        unread.setSelected(unreadOnly);
        int selected = ContextCompat.getColor(activity, R.color.white);
        int normal = ContextCompat.getColor(activity, R.color.text_primary);
        all.setTextColor(unreadOnly ? normal : selected);
        unread.setTextColor(unreadOnly ? selected : normal);
        render();
    }

    private boolean sameSession() {
        return token != null && Objects.equals(token, Session.getToken());
    }

    private boolean canRender() {
        return sameSession() && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void load() {
        if (!resumed || !canRender() || loading || reading) return;
        loading = true;
        int version = ++requestVersion;
        if (!loaded && failure == null) render();
        FleetApi.get(PATH, new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject reply) {
                if (version != requestVersion || !canRender()) return;
                loading = false;
                List<Notice> rows = parse(reply);
                if (rows == null) {
                    failure = activity.getString(R.string.notifications_load_failed);
                    render();
                    return;
                }
                boolean changed = !loaded || failure != null || !notices.equals(rows)
                        || !Objects.equals(renderedDay, dayKey(System.currentTimeMillis()));
                loaded = true;
                failure = null;
                notices = rows;
                if (changed) render();
            }

            @Override public void onError(int httpStatus, String message) {
                if (version != requestVersion || !canRender()) return;
                loading = false;
                failure = message;
                render();
            }
        });
    }

    private static List<Notice> parse(JSONObject reply) {
        JSONArray rows = reply.optJSONArray("notifications");
        if (rows == null) return null;
        List<Notice> result = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optString("id").isEmpty()) continue;
            result.add(new Notice(row.optString("id"), row.optString("title"),
                    row.optString("message"), row.optLong("createdAt"), row.optBoolean("read")));
        }
        result.sort(Comparator.comparingLong(Notice::createdAt).reversed());
        return result;
    }

    private void beginRead() {
        // Discard a list response requested before the first write.
        requestVersion++;
        loading = false;
        reading = true;
        render();
    }

    private void markAllRead() {
        if (reading || !canRender()) return;
        beginRead();
        // Include notifications that arrived since the last refresh.
        FleetApi.get(PATH, new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject reply) {
                if (!sameSession()) return;
                List<Notice> latest = parse(reply);
                if (latest == null) {
                    finishRead(activity.getString(R.string.notifications_load_failed));
                    return;
                }
                loaded = true;
                failure = null;
                notices = latest;
                List<Notice> pending = new ArrayList<>();
                for (Notice notice : notices) {
                    if (!notice.read()) pending.add(notice);
                }
                readNext(pending.iterator());
            }

            @Override public void onError(int httpStatus, String message) {
                finishRead(message);
            }
        });
    }

    private void markRead(Notice notice) {
        if (reading || !canRender() || notice.read()) return;
        beginRead();
        readNext(Collections.singletonList(notice).iterator());
    }

    private void readNext(Iterator<Notice> pending) {
        if (!sameSession()) return;
        if (!pending.hasNext()) {
            finishRead(null);
            return;
        }
        Notice notice = pending.next();
        FleetApi.put(PATH + "/" + FleetApi.encode(notice.id()) + "/read", null, new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!sameSession()) return;
                if (!result.optBoolean("read")) {
                    finishRead(activity.getString(R.string.notifications_update_failed));
                    return;
                }
                int index = notices.indexOf(notice);
                if (index >= 0) notices.set(index, notice.markedRead());
                readNext(pending);
            }

            @Override public void onError(int httpStatus, String message) {
                finishRead(message);
            }
        });
    }

    private void finishRead(String error) {
        reading = false;
        if (!canRender()) return;
        if (error != null) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
        render();
        load();
    }

    private void render() {
        int unreadCount = 0;
        for (Notice notice : notices) {
            if (!notice.read()) unreadCount++;
        }
        count.setText(activity.getString(R.string.notifications_unread_count, unreadCount));
        count.setVisibility(notices.isEmpty() ? View.GONE : View.VISIBLE);
        markAll.setVisibility(unreadCount == 0 ? View.INVISIBLE : View.VISIBLE);
        markAll.setEnabled(!reading);

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"));
        renderedDay = dayKey(calendar.getTimeInMillis());
        calendar.add(Calendar.DATE, -1);
        String yesterday = dayKey(calendar.getTimeInMillis());

        cards.removeAllViews();
        String previousDay = null;
        for (Notice notice : notices) {
            if (unreadOnly && notice.read()) continue;
            long timestamp = notice.createdAt();
            String day = dayKey(timestamp);
            String label = dateLabel(timestamp, day, yesterday);
            if (!Objects.equals(previousDay, day)) {
                TextView heading = (TextView) activity.getLayoutInflater()
                        .inflate(R.layout.item_notification_date, cards, false);
                heading.setText(label);
                cards.addView(heading);
                previousDay = day;
            }
            addCard(notice, day.equals(renderedDay) ? "" : label);
        }
        renderPlaceholder();
    }

    private String dateLabel(long timestamp, String day, String yesterday) {
        if (day.equals(renderedDay)) return activity.getString(R.string.notifications_today);
        if (day.equals(yesterday)) return activity.getString(R.string.notifications_yesterday);
        if (timestamp <= 0) return activity.getString(R.string.notifications_unknown_date);
        return ShiftTime.timestamp(timestamp, "MMM d, yyyy");
    }

    private static String dayKey(long timestamp) {
        return timestamp <= 0 ? "" : ShiftTime.timestamp(timestamp, "yyyy-MM-dd");
    }

    private void addCard(Notice notice, String timePrefix) {
        View card = activity.getLayoutInflater().inflate(R.layout.item_notification, cards, false);
        ((ImageView) card.findViewById(R.id.ivNotificationIcon)).setImageResource(notice.icon());
        ((TextView) card.findViewById(R.id.tvNotificationTitle)).setText(notice.title());
        ((TextView) card.findViewById(R.id.tvNotificationMessage)).setText(notice.message());
        TextView time = card.findViewById(R.id.tvNotificationTime);
        if (notice.createdAt() > 0) {
            String clock = ShiftTime.timestamp(notice.createdAt(), "h:mm a");
            time.setText(timePrefix.isEmpty() ? clock
                    : activity.getString(R.string.notifications_dated_time, timePrefix, clock));
        } else {
            time.setVisibility(View.GONE);
        }
        if (!notice.read()) {
            card.setBackgroundResource(R.drawable.bg_notification_unread);
            card.findViewById(R.id.viewNotificationUnread).setVisibility(View.VISIBLE);
            ViewCompat.setStateDescription(card, activity.getString(R.string.notifications_unread_tab));
            card.setFocusable(true);
            card.setEnabled(!reading);
            card.setOnClickListener(v -> markRead(notice));
        }
        cards.addView(card);
    }

    private void renderPlaceholder() {
        boolean empty = cards.getChildCount() == 0;
        placeholder.setVisibility(empty || failure != null ? View.VISIBLE : View.GONE);
        if (failure != null) {
            status.setText(R.string.notifications_load_failed);
            description.setText(activity.getString(R.string.notifications_retry_detail, failure));
            placeholder.setOnClickListener(v -> load());
            return;
        }
        placeholder.setOnClickListener(null);
        placeholder.setClickable(false);
        status.setText(!loaded ? R.string.notifications_loading
                : unreadOnly ? R.string.notifications_none_unread : R.string.no_notifications);
        description.setText(!loaded ? "" : activity.getString(unreadOnly
                ? R.string.notifications_none_unread_detail : R.string.no_notifications_detail));
    }
}
