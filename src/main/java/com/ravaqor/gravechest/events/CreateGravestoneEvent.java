package com.ravaqor.gravechest.events;

import com.mojang.authlib.GameProfile;
import com.ravaqor.gravechest.GravechestMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
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
        if (!(livingEntity instanceof PlayerEntity player)) return true;

        if (hasUsableTotem(player) && canTotemSaveFrom(damageSource)) {
            return true;
        }

        PlayerEntity killer = getKillingPlayer(player, damageSource);
        if (killer != null) {
            killer.sendMessage(Text.of(player.getStringifiedName() + " hat den Kopf verloren"), true);
        }


        Inventory playerInventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        if(killer != null) {
            items.add(getPlayerSkull(player));
        }

        for (int i = 0; i < playerInventory.size(); i++) {
            ItemStack stack = playerInventory.getStack(i);
            if (!stack.isEmpty()) {
                items.add(playerInventory.getStack(i));
            }
        }
        if (items.isEmpty()) return true;

        World world = player.getEntityWorld();
        BlockPos deathPos = player.getBlockPos();

        if (ALLOW_GRAVESTONE_ON_VOID_DEATH) {
            deathPos = normalizeDeathPos(deathPos, world);
        }
        if (items.size() <= SINGLE_CHEST_SIZE) {
            BlockPos chestPos = findNearestAvailableSinglePos(world, deathPos, GRAVE_SEARCH_RADIUS);
            if (chestPos != null) {
                world.setBlockState(chestPos, Blocks.CHEST.getDefaultState(), 3);
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

    private static boolean hasUsableTotem(PlayerEntity player) {
        return player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    private static boolean canTotemSaveFrom(DamageSource damageSource) {
        return !damageSource.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Nullable
    private static PlayerEntity getKillingPlayer(PlayerEntity victim, DamageSource damageSource) {
        Entity attacker = damageSource.getAttacker();
        if (attacker instanceof PlayerEntity player) {
            return player;
        }

        Entity source = damageSource.getSource();
        if (source instanceof PlayerEntity player) {
            return player;
        }

        LivingEntity recentAttacker = victim.getAttacker();
        if (recentAttacker instanceof PlayerEntity player) {
            return player;
        }

        LivingEntity primeAdversary = victim.getPrimeAdversary();
        if (primeAdversary instanceof PlayerEntity player) {
            return player;
        }

        return null;
    }

    private static ItemStack getPlayerSkull(PlayerEntity target) {
        if (!(target instanceof ServerPlayerEntity serverPlayer)) {
            // If it's not a server player, we can't easily fetch the skin texture without async calls.
            // Fallback to the basic profile (will show default skin) or throw an error.
            // For now, let's try to get the profile from the server's profile repository if possible.
            // But usually, this function is called with a ServerPlayerEntity.
            return null;
        }
        GameProfile fullProfile = serverPlayer.getGameProfile();

        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(fullProfile));

        return stack;
    }

    private static void placeDoubleChest(DoubleBlockTuple chestPos, World world) {
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
    }

    private static void moveItems(List<ItemStack> items, PlayerEntity player, BlockPos chestPos) {
        World world = player.getEntityWorld();
        BlockPos deathPos = player.getBlockPos();
        PlayerInventory playerInventory = player.getInventory();

        BlockState state = world.getBlockState(chestPos);

        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            GravechestMod.LOGGER.error("Invalid chest block entity at {}", deathPos);
            return;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
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

    private static DoubleBlockTuple findNearestAvailableDoublePos(World world, BlockPos origin, int radius) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    BlockPos newPos = origin.add(x, y, z);
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

    private static BlockPos findNearestAvailableSinglePos(World world, BlockPos origin, int radius) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    BlockPos newPos = origin.add(x, y, z);
                    if (checkBlockPlaceability(world, newPos)) {
                        return newPos;
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

    /**
     * Moves y-coordinate of the deathPos up until the {@link net.minecraft.world.dimension.DimensionType} minY is
     * reached.
     *
     * @param deathPos The position where the player died.
     * @param world The current world the player is in
     * @return Gives back the new position, which is located at the players x and z coordinates, but y level of the
     * min block limit of the corresponding dimension
     */
    private BlockPos normalizeDeathPos(BlockPos deathPos, World world) {
        int distance = 0;
        if (deathPos.getY() < world.getDimension().minY()) {
            distance = world.getDimension().minY() - deathPos.getY();
        }
        return deathPos.up(distance);
    }

}
