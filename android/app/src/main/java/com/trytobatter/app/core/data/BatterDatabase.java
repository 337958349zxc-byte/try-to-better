package com.trytobatter.app.core.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {SnapshotEntity.class}, version = 1, exportSchema = false)
public abstract class BatterDatabase extends RoomDatabase {
    public abstract SnapshotDao snapshotDao();
}
