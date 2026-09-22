package moe.reimu.nekoassistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "llm_providers")
data class LlmProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
)

@Entity(
    tableName = "llm_models",
    foreignKeys = [
        ForeignKey(
            entity = LlmProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("providerId")],
)
data class LlmModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val name: String,
    val isSelected: Boolean = false,
)

data class LlmProviderWithModels(
    @Embedded val provider: LlmProviderEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "providerId",
    )
    val models: List<LlmModelEntity>,
)

data class ActiveLlmConfiguration(
    val providerName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
)

@Dao
interface LlmConfigurationDao {
    @Transaction
    @Query("SELECT * FROM llm_providers ORDER BY name COLLATE NOCASE")
    fun observeProviders(): Flow<List<LlmProviderWithModels>>

    @Query(
        """
        SELECT p.name AS providerName, p.baseUrl AS baseUrl, p.apiKey AS apiKey, m.name AS modelName
        FROM llm_providers p
        INNER JOIN llm_models m ON m.providerId = p.id
        WHERE m.isSelected = 1
        LIMIT 1
        """
    )
    suspend fun activeConfiguration(): ActiveLlmConfiguration?

    @Insert
    suspend fun insertProvider(provider: LlmProviderEntity): Long

    @Insert
    suspend fun insertModel(model: LlmModelEntity): Long

    @Query("UPDATE llm_providers SET name = :name, baseUrl = :baseUrl, apiKey = :apiKey WHERE id = :id")
    suspend fun updateProvider(id: Long, name: String, baseUrl: String, apiKey: String)

    @Query("UPDATE llm_models SET name = :name WHERE id = :id")
    suspend fun updateModel(id: Long, name: String)

    @Delete
    suspend fun deleteProvider(provider: LlmProviderEntity)

    @Delete
    suspend fun deleteModel(model: LlmModelEntity)

    @Query("UPDATE llm_models SET isSelected = 0")
    suspend fun clearSelectedModels()

    @Query("UPDATE llm_models SET isSelected = 1 WHERE id = :modelId")
    suspend fun selectModelById(modelId: Long)

    @Query("SELECT id FROM llm_models WHERE providerId = :providerId ORDER BY id LIMIT 1")
    suspend fun firstModelId(providerId: Long): Long?

    @Query("SELECT id FROM llm_models ORDER BY providerId, id LIMIT 1")
    suspend fun firstAvailableModelId(): Long?

    @Transaction
    suspend fun selectModel(modelId: Long) {
        clearSelectedModels()
        selectModelById(modelId)
    }

    @Transaction
    suspend fun selectFallbackIfNeeded(preferredProviderId: Long? = null) {
        if (activeConfiguration() != null) {
            return
        }
        val fallbackModelId = if (preferredProviderId != null) {
            firstModelId(preferredProviderId) ?: firstAvailableModelId()
        } else {
            firstAvailableModelId()
        }
        if (fallbackModelId != null) {
            selectModel(fallbackModelId)
        }
    }
}

@Database(
    entities = [LlmProviderEntity::class, LlmModelEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LlmConfigurationDatabase : RoomDatabase() {
    abstract fun llmConfigurationDao(): LlmConfigurationDao

    companion object {
        @Volatile
        private var instance: LlmConfigurationDatabase? = null

        fun getInstance(context: Context): LlmConfigurationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LlmConfigurationDatabase::class.java,
                    "llm-configuration.db",
                ).build().also { instance = it }
            }
    }
}

class LlmConfigurationRepository(context: Context) {
    private val database = LlmConfigurationDatabase.getInstance(context)
    private val dao = database.llmConfigurationDao()

    fun observeProviders(): Flow<List<LlmProviderWithModels>> = dao.observeProviders()

    suspend fun activeConfiguration(): ActiveLlmConfiguration? = dao.activeConfiguration()

    suspend fun createProvider(name: String, baseUrl: String, apiKey: String) {
        dao.insertProvider(
            LlmProviderEntity(
                name = name.trim(),
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
            )
        )
    }

    suspend fun saveProvider(provider: LlmProviderEntity, name: String, baseUrl: String, apiKey: String) {
        dao.updateProvider(provider.id, name.trim(), baseUrl.trim(), apiKey.trim())
    }

    suspend fun deleteProvider(provider: LlmProviderEntity) = database.withTransaction {
        dao.deleteProvider(provider)
        dao.selectFallbackIfNeeded()
    }

    suspend fun createModel(providerId: Long, name: String) = database.withTransaction {
        val modelId = dao.insertModel(LlmModelEntity(providerId = providerId, name = name.trim()))
        dao.selectModel(modelId)
    }

    suspend fun saveModel(model: LlmModelEntity, name: String) = dao.updateModel(model.id, name.trim())

    suspend fun deleteModel(model: LlmModelEntity) = database.withTransaction {
        dao.deleteModel(model)
        dao.selectFallbackIfNeeded(model.providerId)
    }

    suspend fun selectModel(modelId: Long) = dao.selectModel(modelId)
}
