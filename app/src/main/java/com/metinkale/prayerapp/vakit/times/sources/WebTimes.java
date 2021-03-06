/*
 * Copyright (c) 2013-2017 Metin Kale
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.metinkale.prayerapp.vakit.times.sources;


import android.support.annotation.NonNull;
import android.support.v4.util.ArrayMap;
import android.support.v4.util.ArraySet;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.CustomEvent;
import com.evernote.android.job.Job;
import com.evernote.android.job.JobManager;
import com.evernote.android.job.JobRequest;
import com.metinkale.prayer.R;
import com.metinkale.prayerapp.App;
import com.metinkale.prayerapp.utils.FastParser;
import com.metinkale.prayerapp.vakit.times.Source;
import com.metinkale.prayerapp.vakit.times.Times;

import org.joda.time.LocalDate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


public abstract class WebTimes extends Times {

    @NonNull
    protected ArrayMap<String, String> times = new ArrayMap<>();
    private String id;
    private int jobId = -1;
    private long lastSync;

    protected WebTimes(long id) {
        super(id);
        App.get().getHandler().post(new Runnable() {
            @Override
            public void run() {
                scheduleJob();
            }
        });
    }

    protected WebTimes() {
        super();
        App.get().getHandler().post(new Runnable() {
            @Override
            public void run() {
                scheduleJob();
            }
        });
    }


    @NonNull
    public static Times add(@NonNull Source source, String city, String id, double lat, double lng) {
        return add(source, city, id, lat, lng, System.currentTimeMillis());
    }

    @NonNull
    public static Times add(@NonNull Source source, String city, String id, double lat, double lng, long _id) {
        if (source == Source.Calc) throw new RuntimeException("Calc is not a WebTimes");
        WebTimes t;
        try {
            t = (WebTimes) source.clz.getConstructor(long.class).newInstance(_id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        t.setSource(source);
        t.setName(city);
        t.setLat(lat);
        t.setLng(lng);
        t.setId(id);
        t.setSortId(99);
        t.scheduleJob();

        Answers.getInstance().logCustom(new CustomEvent("AddCity")
                .putCustomAttribute("Source", source.name())
                .putCustomAttribute("City", city)
        );
        return t;
    }


    @Override
    public void delete() {
        super.delete();
        if (jobId != -1)
            JobManager.instance().cancel(jobId);
    }

    @Override
    public String getTime(LocalDate date, int time) {
        return super.getTime(date, time);
    }


    String extractLine(String str) {
        str = str.substring(str.indexOf(">") + 1);
        str = str.substring(0, str.indexOf("</"));
        return str;
    }


    @Override
    protected synchronized String _getTime(@NonNull LocalDate date, int time) {
        String str = times.get(date.toString("yyyy-MM-dd") + "-" + time);
        if (str == null || str.isEmpty() || str.contains("00:00")) {
            return "00:00";
        }
        return str.replace("*", "");
    }

    private synchronized void setTime(@NonNull LocalDate date, int time, @NonNull String value) {
        if (isDeleted() || value.contains("00:00")) return;
        times.put(date.toString("yyyy-MM-dd") + "-" + time, value.replace("*", ""));
        save();
    }

    void setTimes(@NonNull LocalDate date, @NonNull String[] value) {
        if (isDeleted()) return;
        for (int i = 0; i < value.length; i++) {
            setTime(date, i, value[i]);
        }

    }

    public synchronized String getId() {
        //TODO remove if after a few updates
        if (id.charAt(1) == '_' && 'A' <= id.charAt(0) && 'Z' >= id.charAt(0))//backwart support
        {
            id = id.substring(2);
            save();
        }
        return id;
    }

    public synchronized void setId(String id) {
        this.id = id;
        save();
    }


    abstract boolean sync() throws ExecutionException, InterruptedException;

    public void syncAsync() {
        cleanTimes();
        if (!App.isOnline()) {
            Toast.makeText(App.get(), R.string.no_internet, Toast.LENGTH_SHORT).show();
            scheduleJob();
            return;
        }
        if (getId() == null) return;

        new Thread("SyncWebTimes" + getId()) {
            @Override
            public void run() {
                try {
                    if (sync()) {
                        App.get().getHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                notifyOnUpdated();
                                Times.setAlarms();
                            }
                        });
                    }
                    } catch (Exception e) {
                    Crashlytics.logException(e);
                }
            }
        }.start();
    }


    private int getSyncedDays() {
        LocalDate date = LocalDate.now().plusDays(1);
        int i = 0;
        while (i < 45) {
            String prefix = date.toString("yyyy-MM-dd") + "-";
            String times[] = {
                    this.times.get(prefix + 0),
                    this.times.get(prefix + 1),
                    this.times.get(prefix + 2),
                    this.times.get(prefix + 3),
                    this.times.get(prefix + 4),
                    this.times.get(prefix + 5)
            };
            for (String time : times) {
                if (time == null || time.contains("00:00")) return i;
            }
            i++;
            date = date.plusDays(1);
        }
        return i;

    }

    @NonNull
    public LocalDate getFirstSyncedDay() {
        LocalDate date = LocalDate.now();
        int i = 0;
        while (true) {
            String prefix = date.toString("yyyy-MM-dd") + "-";
            String times[] = {
                    this.times.get(prefix + 0),
                    this.times.get(prefix + 1),
                    this.times.get(prefix + 2),
                    this.times.get(prefix + 3),
                    this.times.get(prefix + 4),
                    this.times.get(prefix + 5)
            };
            for (String time : times) {
                if (time == null || time.contains("00:00") || i > this.times.size())
                    return date.plusDays(1);
            }
            i++;
            date = date.minusDays(1);
        }
    }

    @NonNull
    public LocalDate getLastSyncedDay() {
        LocalDate date = LocalDate.now();
        int i = 0;
        while (true) {
            String prefix = date.toString("yyyy-MM-dd") + "-";
            String times[] = {
                    this.times.get(prefix + 0),
                    this.times.get(prefix + 1),
                    this.times.get(prefix + 2),
                    this.times.get(prefix + 3),
                    this.times.get(prefix + 4),
                    this.times.get(prefix + 5)
            };
            for (String time : times) {
                if (time == null || time.contains("00:00") || i > this.times.size())
                    return date.minusDays(1);
            }
            i++;
            date = date.plusDays(1);
        }
    }


    private void scheduleJob() {
        int syncedDays = getSyncedDays();


        if (syncedDays == 0 && System.currentTimeMillis() - lastSync < 1000 * 60 * 60) {
            lastSync = System.currentTimeMillis();
            if (App.isOnline()) syncAsync();
            else
                jobId = new JobRequest.Builder(SyncJob.TAG + getID())
                        .setExecutionWindow(1, TimeUnit.MINUTES.toMillis(3))
                        .setRequiredNetworkType(JobRequest.NetworkType.CONNECTED)
                        .setBackoffCriteria(TimeUnit.MINUTES.toMillis(3), JobRequest.BackoffPolicy.EXPONENTIAL)
                        .setUpdateCurrent(true)
                        .build()
                        .schedule();

        } else if (syncedDays < 3)
            jobId = new JobRequest.Builder(SyncJob.TAG + getID())
                    .setExecutionWindow(1, TimeUnit.HOURS.toMillis(3))
                    .setRequiredNetworkType(JobRequest.NetworkType.CONNECTED)
                    .setUpdateCurrent(true)
                    .setBackoffCriteria(TimeUnit.HOURS.toMillis(1), JobRequest.BackoffPolicy.EXPONENTIAL)
                    .build()
                    .schedule();
        else if (syncedDays < 10)
            jobId = new JobRequest.Builder(SyncJob.TAG + getID())
                    .setExecutionWindow(1, TimeUnit.DAYS.toMillis(3))
                    .setBackoffCriteria(TimeUnit.DAYS.toMillis(1), JobRequest.BackoffPolicy.LINEAR)
                    .setRequiredNetworkType(JobRequest.NetworkType.CONNECTED)
                    .setRequiresCharging(true)
                    .setUpdateCurrent(true)
                    .build()
                    .schedule();
        else if (syncedDays < 20)
            jobId = new JobRequest.Builder(SyncJob.TAG + getID())
                    .setExecutionWindow(1, TimeUnit.DAYS.toMillis(10))
                    .setRequiredNetworkType(JobRequest.NetworkType.UNMETERED)
                    .setBackoffCriteria(TimeUnit.DAYS.toMillis(3), JobRequest.BackoffPolicy.LINEAR)
                    .setUpdateCurrent(true)
                    .build()
                    .schedule();

    }


    public class SyncJob extends Job {
        public static final String TAG = "WebTimesSyncJob";

        @NonNull
        @Override
        protected Result onRunJob(Params params) {
            if (!App.isOnline()) return Result.RESCHEDULE;
            syncAsync();
            return Result.SUCCESS;
        }


        @Override
        protected void onReschedule(int newJobId) {
            jobId = newJobId;
        }


    }

    private void cleanTimes() {
        Set<String> keys = new ArraySet<>();
        keys.addAll(times.keySet());
        LocalDate date = LocalDate.now();
        int y = date.getYear();
        int m = date.getMonthOfYear();
        List<String> remove = new ArrayList<>();
        for (String key : keys) {
            if (key == null) continue;
            int year = FastParser.parseInt(key.substring(0, 4));
            if (year < y) keys.remove(key);
            else if (year == y) {
                int month = FastParser.parseInt(key.substring(5, 7));
                if (month < m) remove.add(key);
            }
        }
        times.removeAll(remove);

    }

    protected void clearTimes() {
        times.clear();
    }

}
