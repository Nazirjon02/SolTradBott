package tj.khujand.solana.trading.bot.util

import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString


/**
 * Управление настройками приложения с поддержкой различных типов данных
 */
object AppSettings {

    private var settings: Settings = try {
        Settings()
    } catch (e: Throwable) {
        // Fallback для случаев, когда Settings() не может создаться (превью, тесты)
        createMockSettings()
    }

    /**
     * Сохранить строковое значение
     */
    fun putString(key: String, value: String) {
        settings[key] = value
    }

    /**
     * Получить строковое значение с дефолтным значением
     */
    fun getStringSafe(key: String, defaultValue: String = ""): String {
        return settings.getString(key, defaultValue)
    }

    /**
     * Сохранить целочисленное значение
     */
    fun putInt(key: String, value: Int) {
        settings[key] = value
    }

    /**
     * Получить целочисленное значение с дефолтным значением
     */
    fun getIntSafe(key: String, defaultValue: Int = 0): Int {
        return settings.getInt(key, defaultValue)
    }

    /**
     * Сохранить значение с плавающей точкой (Double)
     */
    fun putDouble(key: String, value: Double) {
        settings[key] = value
    }

    /**
     * Получить значение Double с дефолтным значением
     */
    fun getDoubleSafe(key: String, defaultValue: Double = 0.0): Double {
        return settings.getDouble(key, defaultValue)
    }

    /**
     * Сохранить булево значение
     */
    fun putBoolean(key: String, value: Boolean) {
        settings[key] = value
    }

    /**
     * Получить булево значение с дефолтным значением
     */
    fun getBooleanSafe(key: String, defaultValue: Boolean = false): Boolean {
        return settings.getBoolean(key, defaultValue)
    }

    /**
     * Сохранить Long значение
     */
    fun putLong(key: String, value: Long) {
        settings[key] = value
    }

    /**
     * Получить Long значение с дефолтным значением
     */
    fun getLongSafe(key: String, defaultValue: Long = 0L): Long {
        return settings.getLong(key, defaultValue)
    }

    /**
     * Сохранить Float значение
     */
    fun putFloat(key: String, value: Float) {
        settings[key] = value
    }

    /**
     * Получить Float значение с дефолтным значением
     */
    fun getFloatSafe(key: String, defaultValue: Float = 0f): Float {
        return settings.getFloat(key, defaultValue)
    }

    /**
     * Удалить значение по ключу
     */
    fun remove(key: String) {
        try {
            if (settings is MockSettings) {
                (settings as MockSettings).remove(key)
            } else {
                // Для реального Settings используем стандартный способ
                settings.remove(key)
            }
        } catch (e: Exception) {
            // Игнорируем ошибки удаления
        }
    }

    /**
     * Очистить все настройки
     */
    fun clearAll() {
        try {
            settings.clear()
        } catch (e: Exception) {
            // Игнорируем ошибки очистки
        }
    }

    /**
     * Проверить наличие ключа
     */
    fun containsKey(key: String): Boolean {
        return try {
            settings.hasKey(key)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Получить все сохраненные ключи
     */
    fun getAllKeys(): Set<String> {
        return try {
            settings.keys
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Сохранить сериализуемый объект как JSON
     */
    inline fun <reified T> putObject(key: String, value: T) {
        try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
            val jsonString = json.encodeToString(value)
            putString(key, jsonString)
        } catch (e: Exception) {
            println("Ошибка при сохранении объекта: ${e.message}")
        }
    }

    /**
     * Загрузить сериализуемый объект из JSON
     */
    inline fun <reified T> getObjectSafe(key: String, defaultValue: T): T {
        return try {
            val jsonString = getStringSafe(key, "")
            if (jsonString.isEmpty()) {
                defaultValue
            } else {
                val json = kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
                json.decodeFromString<T>(jsonString)
            }
        } catch (e: Exception) {
            println("Ошибка при загрузке объекта: ${e.message}")
            defaultValue
        }
    }

    /**
     * Создать заглушку для Settings для использования в превью и тестах
     */
    private fun createMockSettings(): Settings {
        return MockSettings()
    }

    /**
     * Mock реализация Settings для использования в превью и тестах
     */
    private class MockSettings : Settings {
        private val storage = mutableMapOf<String, Any>()

        override val keys: Set<String> get() = storage.keys
        override val size: Int get() = storage.size

        override fun clear() {
            storage.clear()
        }

        override fun remove(key: String) {
            storage.remove(key)
        }

        override fun hasKey(key: String): Boolean = storage.containsKey(key)

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return storage[key] as? Boolean ?: defaultValue
        }

        override fun getBooleanOrNull(key: String): Boolean? {
            TODO("Not yet implemented")
        }

        override fun getDouble(key: String, defaultValue: Double): Double {
            return storage[key] as? Double ?: defaultValue
        }

        override fun getDoubleOrNull(key: String): Double? {
            TODO("Not yet implemented")
        }

        override fun getFloat(key: String, defaultValue: Float): Float {
            return storage[key] as? Float ?: defaultValue
        }

        override fun getFloatOrNull(key: String): Float? {
            TODO("Not yet implemented")
        }

        override fun getInt(key: String, defaultValue: Int): Int {
            return storage[key] as? Int ?: defaultValue
        }

        override fun getIntOrNull(key: String): Int? {
            TODO("Not yet implemented")
        }

        override fun getLong(key: String, defaultValue: Long): Long {
            return storage[key] as? Long ?: defaultValue
        }

        override fun getLongOrNull(key: String): Long? {
            TODO("Not yet implemented")
        }

        override fun getString(key: String, defaultValue: String): String {
            return storage[key] as? String ?: defaultValue
        }

        override fun getStringOrNull(key: String): String? {
            TODO("Not yet implemented")
        }

        override fun putBoolean(key: String, value: Boolean) {
            storage[key] = value
        }

        override fun putDouble(key: String, value: Double) {
            storage[key] = value
        }

        override fun putFloat(key: String, value: Float) {
            storage[key] = value
        }

        override fun putInt(key: String, value: Int) {
            storage[key] = value
        }

        override fun putLong(key: String, value: Long) {
            storage[key] = value
        }

        override fun putString(key: String, value: String) {
            storage[key] = value
        }
    }
}

/**
 * Расширения для Settings для удобства
 */
private operator fun Settings.set(key: String, value: Any) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Double -> putDouble(key, value)
        is String -> putString(key, value)
        else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
    }
}