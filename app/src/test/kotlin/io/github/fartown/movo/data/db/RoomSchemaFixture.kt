package io.github.fartown.movo.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import org.json.JSONObject

/**
 * 按 app/schemas 里导出的表结构建出指定版本的空数据库，供迁移测试写入旧数据后再用 Room 迁移打开
 * （Robolectric 单测读不到测试资源，用不了 MigrationTestHelper）。
 */
internal object RoomSchemaFixture {
    fun create(context: Context, name: String, version: Int): SupportSQLiteDatabase {
        val schema = JSONObject(File("schemas/${MovoDatabase::class.java.name}/$version.json").readText())
            .getJSONObject("database")
        val statements = buildList {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                add(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) add(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) add(setup.getString(index))
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }
}
