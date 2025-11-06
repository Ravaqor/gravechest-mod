package com.ravaqor.gravechest.events;

import com.ravaqor.gravechest.GravechestMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class CreateGravestoneEvent implements ServerLivingEntityEvents.AllowDeath {

    public static int GRAVE_SEARCH_RADIUS = 5;

    @Override
    public boolean allowDeath(LivingEntity livingEntity, DamageSource damageSource, float v) {
        if (!(livingEntity instanceof PlayerEntity player)) return true;

        World world = player.getEntityWorld();
        BlockPos deathPos = player.getBlockPos();

        BlockState chest = Blocks.CHEST.getDefaultState();

        var chestPos = findNearestAvailablePos(world, deathPos, GRAVE_SEARCH_RADIUS);

        if (chestPos != null) {
            if (!player.getInventory().isEmpty()) {
                world.setBlockState(chestPos, chest);
                BlockEntity blockEntity = world.getBlockEntity(chestPos);

                if (!(blockEntity instanceof ChestBlockEntity chestEntity)) {
                    GravechestMod.LOGGER.error("Invalid chest block entity at {}", deathPos);
                    return false;
                }
                Inventory inventory = player.getInventory();
                int slotCounter = 0;
                for (int i = 0; i < inventory.size(); i++) {
                    ItemStack stack = inventory.getStack(i);
                    if (!stack.isEmpty()) {
                        chestEntity.setStack(slotCounter, stack);
                        slotCounter++;
                    }
                }
                inventory.clear();
            }
        }
        return true;
    }

    private static BlockPos findNearestAvailablePos(World world, BlockPos origin, int radius) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    BlockPos newPos = origin.add(x, y, z);
                    if (world.isAir(newPos) || world.getBlockState(newPos).getBlock().getDefaultState().isReplaceable()) {
                        return newPos;
                    }
                }
            }
        }
        return null;
    }
}
