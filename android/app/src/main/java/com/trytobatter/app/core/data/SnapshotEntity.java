package com.trytobatter.app.core.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_snapshot")
public class SnapshotEntity {
    @PrimaryKey public int id;
    @NonNull public String payload;
    public SnapshotEntity(int id, @NonNull String payload) { this.id = id; this.payload = payload; }
}
