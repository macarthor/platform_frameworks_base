/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.app.usage.TimeSparseArray;
import android.app.usage.UsageStatsManager;
import android.util.AtomicFile;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides an interface to query for UsageStat data from an XML database.
 */
class UsageStatsDatabase {
    private static final int CURRENT_VERSION = 1;

    private static final String TAG = "UsageStatsDatabase";
    private static final boolean DEBUG = UsageStatsService.DEBUG;

    private final Object mLock = new Object();
    private final File[] mIntervalDirs;
    private final TimeSparseArray<AtomicFile>[] mSortedStatFiles;
    private final UnixCalendar mCal;
    private final File mVersionFile;

    public UsageStatsDatabase(File dir) {
        mIntervalDirs = new File[] {
                new File(dir, "daily"),
                new File(dir, "weekly"),
                new File(dir, "monthly"),
                new File(dir, "yearly"),
        };
        mVersionFile = new File(dir, "version");
        mSortedStatFiles = new TimeSparseArray[mIntervalDirs.length];
        mCal = new UnixCalendar(0);
    }

    /**
     * Initialize any directories required and index what stats are available.
     */
    void init() {
        synchronized (mLock) {
            for (File f : mIntervalDirs) {
                f.mkdirs();
                if (!f.exists()) {
                    throw new IllegalStateException("Failed to create directory "
                            + f.getAbsolutePath());
                }
            }

            checkVersionLocked();

            final FilenameFilter backupFileFilter = new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return !name.endsWith(".bak");
                }
            };

            // Index the available usage stat files on disk.
            for (int i = 0; i < mSortedStatFiles.length; i++) {
                mSortedStatFiles[i] = new TimeSparseArray<>();
                File[] files = mIntervalDirs[i].listFiles(backupFileFilter);
                if (files != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Found " + files.length + " stat files for interval " + i);
                    }

                    for (File f : files) {
                        mSortedStatFiles[i].put(Long.parseLong(f.getName()), new AtomicFile(f));
                    }
                }
            }
        }
    }

    private void checkVersionLocked() {
        int version;
        try (BufferedReader reader = new BufferedReader(new FileReader(mVersionFile))) {
            version = Integer.parseInt(reader.readLine());
        } catch (NumberFormatException | IOException e) {
            version = 0;
        }

        if (version != CURRENT_VERSION) {
            Slog.i(TAG, "Upgrading from version " + version + " to " + CURRENT_VERSION);
            doUpgradeLocked(version, CURRENT_VERSION);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(mVersionFile))) {
                writer.write(Integer.toString(CURRENT_VERSION));
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write new version");
                throw new RuntimeException(e);
            }
        }
    }

    private void doUpgradeLocked(int thisVersion, int nextVersion) {
        if (thisVersion == 0) {
            // Delete all files if we are version 0. This is a pre-release version,
            // so this is fine.
            Slog.i(TAG, "Deleting all usage stats files");
            for (int i = 0; i < mIntervalDirs.length; i++) {
                File[] files = mIntervalDirs[i].listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            }
        }
    }

    /**
     * Get the latest stats that exist for this interval type.
     */
    public IntervalStats getLatestUsageStats(int intervalType) {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            final int fileCount = mSortedStatFiles[intervalType].size();
            if (fileCount == 0) {
                return null;
            }

            try {
                final AtomicFile f = mSortedStatFiles[intervalType].valueAt(fileCount - 1);
                IntervalStats stats = new IntervalStats();
                UsageStatsXml.read(f, stats);
                return stats;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to read usage stats file", e);
            }
        }
        return null;
    }

    /**
     * Get the time at which the latest stats begin for this interval type.
     */
    public long getLatestUsageStatsBeginTime(int intervalType) {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            final int statsFileCount = mSortedStatFiles[intervalType].size();
            if (statsFileCount > 0) {
                return mSortedStatFiles[intervalType].keyAt(statsFileCount - 1);
            }
            return -1;
        }
    }

    /**
     * Figures out what to extract from the given IntervalStats object.
     */
    interface StatCombiner<T> {

        /**
         * Implementations should extract interesting from <code>stats</code> and add it
         * to the <code>accumulatedResult</code> list.
         *
         * If the <code>stats</code> object is mutable, <code>mutable</code> will be true,
         * which means you should make a copy of the data before adding it to the
         * <code>accumulatedResult</code> list.
         *
         * @param stats The {@link IntervalStats} object selected.
         * @param mutable Whether or not the data inside the stats object is mutable.
         * @param accumulatedResult The list to which to add extracted data.
         */
        void combine(IntervalStats stats, boolean mutable, List<T> accumulatedResult);
    }

    /**
     * Find all {@link IntervalStats} for the given range and interval type.
     */
    public <T> List<T> queryUsageStats(int intervalType, long beginTime, long endTime,
            StatCombiner<T> combiner) {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            final TimeSparseArray<AtomicFile> intervalStats = mSortedStatFiles[intervalType];

            if (endTime <= beginTime) {
                if (DEBUG) {
                    Slog.d(TAG, "endTime(" + endTime + ") <= beginTime(" + beginTime + ")");
                }
                return null;
            }

            int startIndex = intervalStats.closestIndexOnOrBefore(beginTime);
            if (startIndex < 0) {
                // All the stats available have timestamps after beginTime, which means they all
                // match.
                startIndex = 0;
            }

            int endIndex = intervalStats.closestIndexOnOrBefore(endTime);
            if (endIndex < 0) {
                // All the stats start after this range ends, so nothing matches.
                if (DEBUG) {
                    Slog.d(TAG, "No results for this range. All stats start after.");
                }
                return null;
            }

            if (intervalStats.keyAt(endIndex) == endTime) {
                // The endTime is exclusive, so if we matched exactly take the one before.
                endIndex--;
                if (endIndex < 0) {
                    // All the stats start after this range ends, so nothing matches.
                    if (DEBUG) {
                        Slog.d(TAG, "No results for this range. All stats start after.");
                    }
                    return null;
                }
            }

            try {
                IntervalStats stats = new IntervalStats();
                ArrayList<T> results = new ArrayList<>();
                for (int i = startIndex; i <= endIndex; i++) {
                    final AtomicFile f = intervalStats.valueAt(i);

                    if (DEBUG) {
                        Slog.d(TAG, "Reading stat file " + f.getBaseFile().getAbsolutePath());
                    }

                    UsageStatsXml.read(f, stats);
                    if (beginTime < stats.endTime) {
                        combiner.combine(stats, false, results);
                    }
                }
                return results;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to read usage stats file", e);
                return null;
            }
        }
    }

    /**
     * Find the interval that best matches this range.
     *
     * TODO(adamlesinski): Use endTimeStamp in best fit calculation.
     */
    public int findBestFitBucket(long beginTimeStamp, long endTimeStamp) {
        synchronized (mLock) {
            int bestBucket = -1;
            long smallestDiff = Long.MAX_VALUE;
            for (int i = mSortedStatFiles.length - 1; i >= 0; i--) {
                final int index = mSortedStatFiles[i].closestIndexOnOrBefore(beginTimeStamp);
                int size = mSortedStatFiles[i].size();
                if (index >= 0 && index < size) {
                    // We have some results here, check if they are better than our current match.
                    long diff = Math.abs(mSortedStatFiles[i].keyAt(index) - beginTimeStamp);
                    if (diff < smallestDiff) {
                        smallestDiff = diff;
                        bestBucket = i;
                    }
                }
            }
            return bestBucket;
        }
    }

    /**
     * Remove any usage stat files that are too old.
     */
    public void prune() {
        synchronized (mLock) {
            long timeNow = System.currentTimeMillis();
            mCal.setTimeInMillis(timeNow);
            mCal.addYears(-3);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_YEARLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(timeNow);
            mCal.addMonths(-6);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_MONTHLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(timeNow);
            mCal.addWeeks(-4);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_WEEKLY],
                    mCal.getTimeInMillis());

            mCal.setTimeInMillis(timeNow);
            mCal.addDays(-7);
            pruneFilesOlderThan(mIntervalDirs[UsageStatsManager.INTERVAL_DAILY],
                    mCal.getTimeInMillis());
        }
    }

    private static void pruneFilesOlderThan(File dir, long expiryTime) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                long beginTime = Long.parseLong(f.getName());
                if (beginTime < expiryTime) {
                    new AtomicFile(f).delete();
                }
            }
        }
    }

    /**
     * Update the stats in the database. They may not be written to disk immediately.
     */
    public void putUsageStats(int intervalType, IntervalStats stats) throws IOException {
        synchronized (mLock) {
            if (intervalType < 0 || intervalType >= mIntervalDirs.length) {
                throw new IllegalArgumentException("Bad interval type " + intervalType);
            }

            AtomicFile f = mSortedStatFiles[intervalType].get(stats.beginTime);
            if (f == null) {
                f = new AtomicFile(new File(mIntervalDirs[intervalType],
                        Long.toString(stats.beginTime)));
                mSortedStatFiles[intervalType].put(stats.beginTime, f);
            }

            UsageStatsXml.write(f, stats);
            stats.lastTimeSaved = f.getLastModifiedTime();
        }
    }
}
