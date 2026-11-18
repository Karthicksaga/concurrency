package com.localai.app.di

import android.content.Context
import androidx.room.Room
import com.localai.app.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()

    @Provides fun modelDao(db: AppDatabase) = db.models()
    @Provides fun chatDao(db: AppDatabase) = db.chats()
    @Provides fun agentDao(db: AppDatabase) = db.agents()
    @Provides fun knowledgeDao(db: AppDatabase) = db.knowledge()
    @Provides fun memoryDao(db: AppDatabase) = db.memory()
    @Provides fun noteDao(db: AppDatabase) = db.notes()
    @Provides fun peopleDao(db: AppDatabase) = db.people()
    @Provides fun toolCallDao(db: AppDatabase) = db.toolCalls()
    @Provides fun runDao(db: AppDatabase) = db.runs()
}
