package com.trytobatter.app.core.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface SnapshotDao {
    @Query("SELECT * FROM app_snapshot WHERE id = 1") SnapshotEntity read();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void write(SnapshotEntity entity);
}
