package net.riser876.stacknator.core;

import net.minecraft.world.item.Item;

public class StackItem {

    private final String key;
    private final boolean isDamageable;
    private final int defaultStackSize;
    private int stackSize;

    public StackItem(Item item) {
        this.key = item.toString();
        this.stackSize = item.getDefaultMaxStackSize();
        this.defaultStackSize = item.getDefaultMaxStackSize();
        this.isDamageable = item.getDefaultInstance().isDamageableItem();
    }

    public String getKey() {
        return this.key;
    }

    public int getDefaultStackSize() {
        return this.defaultStackSize;
    }

    public int getStackSize() {
        return this.stackSize;
    }

    public void setStackSize(int stackSize) {
        this.stackSize = stackSize;
    }

    public boolean isDamageable() {
        return this.isDamageable;
    }
}