package net.riser876.stacknator.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.riser876.stacknator.config.ConfigManager;
import net.riser876.stacknator.util.StacknatorGlobals;
import org.slf4j.event.Level;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static net.riser876.stacknator.config.ConfigManager.CONFIG;

public class StackManager {

    private static final Map<String, StackItem> STACK_ITEMS = new HashMap<>();

    public static void init() {
        StacknatorGlobals.log("Processing items...");

        DefaultItemComponentEvents.MODIFY.register(context -> {
            context.modify(
                    StackManager::processItem,
                    (builder, item) -> {
                        builder.set(DataComponents.MAX_STACK_SIZE, CONFIG.ITEMS.get(item.toString()));
                    }
            );
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            StackManager.removeDefaults();
            StackManager.fill();
            StackManager.sortItems();
            StackManager.clear();
        });
    }

    private static boolean processItem(Item item) {
        StackItem stackItem = new StackItem(item);

        STACK_ITEMS.put(stackItem.getKey(), stackItem);

        if (!CONFIG.ITEMS.containsKey(stackItem.getKey())) {
            return false;
        }

        int newStackSize = CONFIG.ITEMS.get(stackItem.getKey());

        if (CONFIG.CHECKS.CHECKS_ENABLED) {
            if (CONFIG.CHECKS.CHECK_DAMAGEABLE && stackItem.isDamageable()) {
                StacknatorGlobals.log("Item {} is damageable. Skipping.", stackItem.getKey());
                return false;
            }

            if (CONFIG.CHECKS.CHECK_STACKABLE && stackItem.getDefaultStackSize() > 1) {
                StacknatorGlobals.log("Item {} is unstackable. Skipping.", stackItem.getKey());
                return false;
            }

            if (CONFIG.CHECKS.CHECK_MINIMUM_STACK_SIZE && newStackSize < 1) {
                StacknatorGlobals.log("Invalid stack size: {} for item {}. Skipping.", newStackSize, stackItem.getKey());
                return false;
            }

            if (CONFIG.CHECKS.CHECK_MAXIMUM_STACK_SIZE && newStackSize > 99) {
                StacknatorGlobals.log("Stack size exceeds the limit: {} for item {}. Changing it to 99.", newStackSize, stackItem.getKey());
                newStackSize = 99;
            }
        }

        if (stackItem.getDefaultStackSize() == newStackSize) {
            return false;
        }

        if (CONFIG.LOG_MODIFIED_ITEMS) {
            StacknatorGlobals.log("Setting stack size of {} to {}.", stackItem.getKey(), newStackSize);
        }

        stackItem.setStackSize(newStackSize);

        return true;
    }

    private static void removeDefaults() {
        try {
            if (!CONFIG.REMOVE_DEFAULTS) {
                StacknatorGlobals.log("Remove defaults disabled. Skipping operation.");
                return;
            }

            StacknatorGlobals.log("Remove defaults enabled. Removing defaults...");

            int removedEntries = 0;

            for (Map.Entry<String, StackItem> entry : STACK_ITEMS.entrySet()) {
                StackItem stackItem = entry.getValue();

                if (stackItem.getStackSize() == stackItem.getDefaultStackSize()) {
                    CONFIG.ITEMS.remove(stackItem.getKey());
                    removedEntries++;
                }
            }

            StacknatorGlobals.log("Remove defaults operation completed. Removed {} entries.", removedEntries);

            ConfigManager.saveConfig();
        } catch (Exception ex) {
            StacknatorGlobals.log(Level.ERROR, "Failed to remove defaults.", ex);
        }
    }

    private static void fill() {
        Map<String, Integer> fallbackItems = null;

        try {
            if (!CONFIG.FILLER.FILLER_ENABLED) {
                StacknatorGlobals.log("Filler disabled. Skipping operation.");
                return;
            }

            fallbackItems = new HashMap<>(CONFIG.ITEMS);
            CONFIG.ITEMS.clear();

            StacknatorGlobals.log("Filler enabled. Loading tags...");

            StackManager.loadTags();

            StacknatorGlobals.log("Filling items...");

            STACK_ITEMS.forEach((key, stackItem) -> StackManager.fillItem(stackItem));

            StacknatorGlobals.log("Filler operation complete.");

            if (CONFIG.FILLER.RUN_ONCE) {
                StacknatorGlobals.log("Disabling filler...");
                CONFIG.FILLER.FILLER_ENABLED = false;
                StacknatorGlobals.log("Filler disabled.");
            }
        } catch (Exception ex) {
            if (fallbackItems != null) {
                CONFIG.ITEMS.clear();
                CONFIG.ITEMS.putAll(fallbackItems);
            }
            StacknatorGlobals.log(Level.ERROR, "Failed to fill items.", ex);
        } finally {
            ConfigManager.saveConfig();
        }
    }

    private static void loadTags() {
        if (CONFIG.FILLER.TAGS.isEmpty()) {
            StacknatorGlobals.log("Empty tags configuration. Skipping operation.");
        } else {
            StacknatorGlobals.log("Processing tags...");
        }

        for (Map.Entry<String, Integer> entry : CONFIG.FILLER.TAGS.entrySet()) {
            String key = entry.getKey();

            if (key.split(":").length != 2 || !key.startsWith("#")) {
                StacknatorGlobals.log("Invalid tag key: {}. Skipping.", key);
                continue;
            }

            int tagStackSize = entry.getValue();

            if (CONFIG.CHECKS.CHECK_MINIMUM_STACK_SIZE && tagStackSize <= 0) {
                StacknatorGlobals.log("Invalid stack size: {} for tag {}. Skipping.", tagStackSize, key);
                continue;
            }

            if (CONFIG.CHECKS.CHECK_MAXIMUM_STACK_SIZE && tagStackSize > 99) {
                StacknatorGlobals.log("Stack size exceeds the limit: {} for tag {}. Changing it to 99.", tagStackSize, key);
                tagStackSize = 99;
            }

            StackManager.loadTag(key, tagStackSize);
        }
    }

    private static void loadTag(String key, int tagStackSize) {
        try {
            Identifier identifier = Identifier.parse(key.replace("#", ""));

            TagKey<Item> tagKey = TagKey.create(BuiltInRegistries.ITEM.key(), identifier);

            Optional<HolderSet.Named<Item>> items = BuiltInRegistries.ITEM.get(tagKey);

            if (items.isEmpty()) {
                StacknatorGlobals.log("Empty tag: {}. Skipping.", key);
                return;
            }

            for (Holder<Item> registryEntry : items.get()) {
                if (STACK_ITEMS.containsKey(registryEntry.value().toString())) {
                    StackItem stackItem = STACK_ITEMS.get(registryEntry.value().toString());

                    if (stackItem.getStackSize() == stackItem.getDefaultStackSize()) {
                        STACK_ITEMS.get(stackItem.getKey()).setStackSize(tagStackSize);
                    } else {
                        if (CONFIG.FILLER.TAG_PRIORITY) {
                            STACK_ITEMS.get(stackItem.getKey()).setStackSize(tagStackSize);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            StacknatorGlobals.log(Level.ERROR, "Failed to load tag: {}. Skipping.", key);
        }
    }

    private static void fillItem(StackItem stackItem) {
        try {
            if (CONFIG.CHECKS.CHECK_DAMAGEABLE && stackItem.isDamageable()) {
                return;
            }

            if (CONFIG.CHECKS.CHECK_STACKABLE && stackItem.getDefaultStackSize() <= 1) {
                return;
            }

            if (CONFIG.CHECKS.CHECK_MINIMUM_STACK_SIZE && stackItem.getStackSize() < 1) {
                return;
            }

            if (CONFIG.CHECKS.CHECK_MAXIMUM_STACK_SIZE && stackItem.getStackSize() > 99) {
                stackItem.setStackSize(99);
            }

            if (CONFIG.FILLER.RESET_STACKS) {
                CONFIG.ITEMS.put(stackItem.getKey(), stackItem.getDefaultStackSize());
            } else {
                CONFIG.ITEMS.put(stackItem.getKey(), stackItem.getStackSize());
            }
        } catch (Exception ex) {
            StacknatorGlobals.log(Level.ERROR, "Failed to fill item: {}. Skipping.", stackItem.getKey(), ex);
        }
    }

    private static void sortItems() {
        try {
            if (!CONFIG.SORT_ITEMS) {
                StacknatorGlobals.log("Item sorting disabled. Skipping operation.");
                return;
            }

            StacknatorGlobals.log("Item sorting enabled. Sorting items...");

            Map<String, Integer> sortedItems = CONFIG.ITEMS.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e1,
                            LinkedHashMap::new
                    ));

            CONFIG.ITEMS.clear();
            CONFIG.ITEMS.putAll(sortedItems);

            StacknatorGlobals.log("Item sorting operation completed.");

            ConfigManager.saveConfig();
        } catch (Exception ex) {
            StacknatorGlobals.log(Level.ERROR, "Failed to sort items.", ex);
        }
    }

    private static void clear() {
        STACK_ITEMS.clear();
        CONFIG.ITEMS.clear();
    }
}