package com.ravaqor.gravechest.events;

import com.mojang.authlib.GameProfile;
import com.ravaqor.gravechest.GravechestMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * When the player dies, a chest is created upon death containing all the players' items.
 * <p>
 * When the dying player's inventory is empty, no chest is created. On the other hand, if the inventory has more slots
 * than a single chest, a double chest is created instead. When no possible location could be found in the specified
 * search radius, no chest is placed and the player drops his items as normal.
 */
public class CreateGravestoneEvent implements ServerLivingEntityEvents.AllowDeath {

    public static int GRAVE_SEARCH_RADIUS = 5;
    private static final int SINGLE_CHEST_SIZE = 27;
    public static boolean ALLOW_GRAVESTONE_ON_VOID_DEATH = true;

    @Override
    public boolean allowDeath(LivingEntity livingEntity, DamageSource damageSource, float v) {
        if (!(livingEntity instanceof Player player)) return true;

        if (hasUsableTotem(player) && canTotemSaveFrom(damageSource)) {
            return true;
        }

        Player killer = getKillingPlayer(player, damageSource);
        if (killer != null) {
            killer.sendOverlayMessage(Component.nullToEmpty(player.getPlainTextName() + " hat den Kopf verloren"));
        }


        Container playerInventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        if(killer != null) {
            items.add(getPlayerSkull(player));
        }

        for (int i = 0; i < playerInventory.getContainerSize(); i++) {
            ItemStack stack = playerInventory.getItem(i);
            if (!stack.isEmpty()) {
                items.add(playerInventory.getItem(i));
            }
        }
        if (items.isEmpty()) return true;

        Level world = player.level();
        BlockPos deathPos = player.blockPosition();

        if (ALLOW_GRAVESTONE_ON_VOID_DEATH) {
            deathPos = normalizeDeathPos(deathPos, world);
        }
        if (items.size() <= SINGLE_CHEST_SIZE) {
            BlockPos chestPos = findNearestAvailableSinglePos(world, deathPos, GRAVE_SEARCH_RADIUS);
            if (chestPos != null) {
                world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
                moveItems(items, player, chestPos);
            }
        } else {
            DoubleBlockTuple chestPos = findNearestAvailableDoublePos(world, deathPos, GRAVE_SEARCH_RADIUS);
            if (chestPos != null) {
                placeDoubleChest(chestPos, world);
                moveItems(items, player, chestPos.posLeft);
            }
        }
        return true;
    }

    private static boolean hasUsableTotem(Player player) {
        return player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)
                || player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    private static boolean canTotemSaveFrom(DamageSource damageSource) {
        return !damageSource.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Nullable
    private static Player getKillingPlayer(Player victim, DamageSource damageSource) {
        Entity attacker = damageSource.getEntity();
        if (attacker instanceof Player player) {
            return player;
        }

        Entity source = damageSource.getDirectEntity();
        if (source instanceof Player player) {
            return player;
        }

        LivingEntity recentAttacker = victim.getLastHurtByMob();
        if (recentAttacker instanceof Player player) {
            return player;
        }

        LivingEntity primeAdversary = victim.getKillCredit();
        if (primeAdversary instanceof Player player) {
            return player;
        }

        return null;
    }

    private static ItemStack getPlayerSkull(Player target) {
        if (!(target instanceof ServerPlayer serverPlayer)) {
            // If it's not a server player, we can't easily fetch the skin texture without async calls.
            // Fallback to the basic profile (will show default skin) or throw an error.
            // For now, let's try to get the profile from the server's profile repository if possible.
            // But usually, this function is called with a ServerPlayerEntity.
            return null;
        }
        GameProfile fullProfile = serverPlayer.getGameProfile();

        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(fullProfile));

        return stack;
    }

    private static void placeDoubleChest(DoubleBlockTuple chestPos, Level world) {
        Direction facing = chestPos.axis == Axis.NORTH_SOUTH ? Direction.EAST : Direction.SOUTH;
        BlockState leftChest = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, facing)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);

        BlockState rightChest = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, facing)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);

        world.setBlock(chestPos.posLeft, leftChest, 3);
        world.setBlock(chestPos.posRight, rightChest, 3);

        leftChest.updateNeighbourShapes(world, chestPos.posLeft, 3);
        rightChest.updateNeighbourShapes(world, chestPos.posRight, 3);
    }

    private static void moveItems(List<ItemStack> items, Player player, BlockPos chestPos) {
        Level world = player.level();
        BlockPos deathPos = player.blockPosition();
        Inventory playerInventory = player.getInventory();

        BlockState state = world.getBlockState(chestPos);

        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            GravechestMod.LOGGER.error("Invalid chest block entity at {}", deathPos);
            return;
        }
        Container chestInventory = ChestBlock.getContainer(chestBlock, state, world, chestPos, true);
        assert chestInventory != null;
        int slotCounter = 0;
        for (ItemStack stack : items) {
            chestInventory.setItem(slotCounter, stack);
            slotCounter++;
        }
        chestInventory.setChanged();
        playerInventory.clearContent();
        playerInventory.setChanged();
    }

    private static DoubleBlockTuple findNearestAvailableDoublePos(Level world, BlockPos origin, int radius) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    BlockPos newPos = origin.offset(x, y, z);
                    if (checkBlockPlaceability(world, newPos)) {
                        if (checkBlockPlaceability(world, newPos.north())) {
                            return new DoubleBlockTuple(newPos.north(), newPos, Axis.NORTH_SOUTH);
                        }
                        if (checkBlockPlaceability(world, newPos.south())) {
                            return new DoubleBlockTuple(newPos, newPos.south(), Axis.NORTH_SOUTH);
                        }
                        if (checkBlockPlaceability(world, newPos.east())) {
                            return new DoubleBlockTuple(newPos.east(), newPos, Axis.EAST_WEST);
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

    private static BlockPos findNearestAvailableSinglePos(Level world, BlockPos origin, int radius) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    BlockPos newPos = origin.offset(x, y, z);
                    if (checkBlockPlaceability(world, newPos)) {
                        return newPos;
                    }
                }
            }
        }
        return null;
    }

    private static boolean checkBlockPlaceability(Level world, BlockPos pos) {
        return world.isEmptyBlock(pos) || world.getBlockState(pos).getBlock().defaultBlockState().canBeReplaced();
    }

    private record DoubleBlockTuple(BlockPos posLeft, BlockPos posRight, Axis axis) {
    }

    private enum Axis {EAST_WEST, NORTH_SOUTH}

    /**
     * Moves y-coordinate of the deathPos up until the {@link net.minecraft.world.level.dimension.DimensionType} minY is
     * reached.
     *
     * @param deathPos The position where the player died.
     * @param world The current world the player is in
     * @return Gives back the new position, which is located at the players x and z coordinates, but y level of the
     * min block limit of the corresponding dimension
     */
    private BlockPos normalizeDeathPos(BlockPos deathPos, Level world) {
        int distance = 0;
        if (deathPos.getY() < world.dimensionType().minY()) {
            distance = world.dimensionType().minY() - deathPos.getY();
        }
        return deathPos.above(distance);
    }

}
