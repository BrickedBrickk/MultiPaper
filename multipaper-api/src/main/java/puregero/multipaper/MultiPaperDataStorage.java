package puregero.multipaper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Asynchronous key-value storage hosted on the Master instance.
 */
public interface MultiPaperDataStorage {

    /**
     * Retrieves the value associated with a key.
     *
     * @param key the key to retrieve
     * @return a future containing the value, or {@code null} if no value is stored
     */
    CompletableFuture<@Nullable String> get(String key);

    /**
     * Retrieves a value, returning a default when the key has no value.
     *
     * @param key the key to retrieve
     * @param def the value to use when the key has no value
     * @return a future containing the stored value or the default
     */
    default CompletableFuture<@Nullable String> get(String key, @Nullable String def) {
        return this.get(key).thenApply(value -> value != null ? value : def);
    }

    /**
     * Retrieves and parses a value as a long.
     *
     * @param key the key to retrieve
     * @return a future containing the parsed value
     */
    default CompletableFuture<Long> getLong(String key) {
        return this.get(key).thenApply(Long::parseLong);
    }

    /**
     * Retrieves and parses a value as a long, returning a default on failure.
     *
     * @param key the key to retrieve
     * @param def the value to use if retrieval or parsing fails
     * @return a future containing the parsed value or the default
     */
    default CompletableFuture<Long> getLong(String key, long def) {
        return this.getLong(key).exceptionally(throwable -> def);
    }

    /**
     * Retrieves and parses a value as an integer.
     *
     * @param key the key to retrieve
     * @return a future containing the parsed value
     */
    default CompletableFuture<Integer> getInt(String key) {
        return this.get(key).thenApply(Integer::parseInt);
    }

    /**
     * Retrieves and parses a value as an integer, returning a default on failure.
     *
     * @param key the key to retrieve
     * @param def the value to use if retrieval or parsing fails
     * @return a future containing the parsed value or the default
     */
    default CompletableFuture<Integer> getInt(String key, int def) {
        return this.getInt(key).exceptionally(throwable -> def);
    }

    /**
     * Retrieves and parses a value as a double.
     *
     * @param key the key to retrieve
     * @return a future containing the parsed value
     */
    default CompletableFuture<Double> getDouble(String key) {
        return this.get(key).thenApply(Double::parseDouble);
    }

    /**
     * Retrieves and parses a value as a double, returning a default on failure.
     *
     * @param key the key to retrieve
     * @param def the value to use if retrieval or parsing fails
     * @return a future containing the parsed value or the default
     */
    default CompletableFuture<Double> getDouble(String key, double def) {
        return this.getDouble(key).exceptionally(throwable -> def);
    }

    /**
     * Lists all stored keys and values.
     *
     * @return a future containing the stored entries
     */
    default CompletableFuture<Map<String, String>> list() {
        return this.list(null);
    }

    /**
     * Lists stored keys and values filtered by a key prefix. A {@code null}
     * prefix matches all entries.
     *
     * @param prefix the required key prefix, or {@code null}
     * @return a future containing the matching entries
     */
    CompletableFuture<Map<String, String>> list(@Nullable String prefix);

    /**
     * Stores a value for a key.
     *
     * @param key   the key to update
     * @param value the value to store
     * @return a future containing the stored value
     */
    CompletableFuture<String> set(String key, String value);

    /**
     * Stores a long value for a key.
     *
     * @param key   the key to update
     * @param value the value to store
     * @return a future containing the stored value
     */
    default CompletableFuture<Long> set(String key, long value) {
        return this.set(key, Long.toString(value)).thenApply(Long::parseLong);
    }

    /**
     * Stores an integer value for a key.
     *
     * @param key   the key to update
     * @param value the value to store
     * @return a future containing the stored value
     */
    default CompletableFuture<Integer> set(String key, int value) {
        return this.set(key, Integer.toString(value)).thenApply(Integer::parseInt);
    }

    /**
     * Stores a double value for a key.
     *
     * @param key   the key to update
     * @param value the value to store
     * @return a future containing the stored value
     */
    default CompletableFuture<Double> set(String key, double value) {
        return this.set(key, Double.toString(value)).thenApply(Double::parseDouble);
    }

    /**
     * Adds a numeric increment to the value associated with a key.
     *
     * @param key       the key to update
     * @param increment the increment to add
     * @return a future containing the updated value
     */
    CompletableFuture<String> add(String key, String increment);

    /**
     * Adds a long increment to the value associated with a key.
     *
     * @param key       the key to update
     * @param increment the increment to add
     * @return a future containing the updated value
     */
    default CompletableFuture<Long> add(String key, long increment) {
        return this.add(key, Long.toString(increment)).thenApply(Long::parseLong);
    }

    /**
     * Adds an integer increment to the value associated with a key.
     *
     * @param key       the key to update
     * @param increment the increment to add
     * @return a future containing the updated value
     */
    default CompletableFuture<Integer> add(String key, int increment) {
        return this.add(key, Integer.toString(increment)).thenApply(Integer::parseInt);
    }

    /**
     * Adds a double increment to the value associated with a key.
     *
     * @param key       the key to update
     * @param increment the increment to add
     * @return a future containing the updated value
     */
    default CompletableFuture<Double> add(String key, double increment) {
        return this.add(key, Double.toString(increment)).thenApply(Double::parseDouble);
    }

}
