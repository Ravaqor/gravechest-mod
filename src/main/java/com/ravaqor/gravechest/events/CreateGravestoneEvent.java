package com.ravaqor.gravechest.events;

import com.ravaqor.gravechest.GravechestMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class CreateGravestoneEvent implements ServerLivingEntityEvents.AllowDeath {

    public static int GRAVE_SEARCH_RADIUS = 5;

    @Override
    public boolean allowDeath(LivingEntity livingEntity, DamageSource damageSource, float v) {
        if (!(livingEntity instanceof PlayerEntity player)) return true;

        Inventory playerInventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < playerInventory.size(); i++) {
            items.add(playerInventory.getStack(i));
        }
        if (items.isEmpty()) return true;

        World world = player.getEntityWorld();
        BlockPos deathPos = player.getBlockPos();

        DoubleBlockTuple chestPos = findNearestAvailablePos(world, deathPos, GRAVE_SEARCH_RADIUS);

        if (chestPos != null) {
            if (!player.getInventory().isEmpty()) {
                Direction facing = chestPos.axis == Axis.NORTH_SOUTH ? Direction.EAST : Direction.SOUTH;
                BlockState leftChest = Blocks.CHEST.getDefaultState()
                        .with(ChestBlock.FACING, facing)
                        .with(ChestBlock.CHEST_TYPE, ChestType.LEFT);

                BlockState rightChest = Blocks.CHEST.getDefaultState()
                        .with(ChestBlock.FACING, facing)
                        .with(ChestBlock.CHEST_TYPE, ChestType.RIGHT);



                world.setBlockState(chestPos.posLeft, leftChest, 3);
                world.setBlockState(chestPos.posRight, rightChest, 3);

                leftChest.updateNeighbors(world, chestPos.posLeft, 3);
                rightChest.updateNeighbors(world, chestPos.posRight, 3);

                BlockState state = world.getBlockState(chestPos.posLeft);

                if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
                    GravechestMod.LOGGER.error("Invalid chest block entity at {}", deathPos);
                    return false;
                }
                Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos.posLeft, true);
                assert chestInventory != null;
                int slotCounter = 0;
                for (ItemStack stack : items) {
                    chestInventory.setStack(slotCounter, stack);
                    slotCounter++;
                }
                chestInventory.markDirty();
                playerInventory.clear();
                playerInventory.markDirty();
            }
        }
        return true;
    }

    private static DoubleBlockTuple findNearestAvailablePos(World world, BlockPos origin, int radius) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    BlockPos newPos = origin.add(x, y, z);
                    if (checkBlockPlaceability(world, newPos)) {
                        if (checkBlockPlaceability(world, newPos.north())) {
                            return new DoubleBlockTuple( newPos.north(), newPos, Axis.NORTH_SOUTH);
                        }
                        if (checkBlockPlaceability(world, newPos.south())) {
                            return new DoubleBlockTuple(newPos, newPos.south(), Axis.NORTH_SOUTH);
                        }
                        if (checkBlockPlaceability(world, newPos.east())) {
                            return new DoubleBlockTuple( newPos.east(), newPos, Axis.EAST_WEST);
                        }
                        if (checkBlockPlaceability(world, newPos.west())) {
                            return new DoubleBlockTuple(newPos, newPos.west(), Axis.EAST_WEST);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean checkBlockPlaceability(World world, BlockPos pos) {
        return world.isAir(pos) || world.getBlockState(pos).getBlock().getDefaultState().isReplaceable();
    }

    private record DoubleBlockTuple(BlockPos posLeft, BlockPos posRight, Axis axis) {
    }

    private enum Axis {EAST_WEST, NORTH_SOUTH}

}
