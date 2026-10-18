package com.stephenjoy.recipes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [RecipeEntity::class, DeletedRecipeEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "recipes.db").build()
    }
}
