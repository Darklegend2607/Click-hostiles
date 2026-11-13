package com.clickhostiles;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class ClickHostiles implements ModInitializer {
    public static final String MOD_ID = "clickhostiles";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String KEY = "clickhostiles_mob";

    private static final Set<EntityType<?>> BLOCKED =
            Set.of(EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN);

    @Override
    public void onInitialize() {
        MobHeads.load();
        UseEntityCallback.EVENT.register(this::onUseEntity);
        UseBlockCallback.EVENT.register(this::onUseBlock);
        UseItemCallback.EVENT.register(this::onUseItem);
    }

    // ---- pick up -------------------------------------------------------------------------------

    private ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity,
                                     net.minecraft.util.hit.EntityHitResult hit) {
        if (hand != Hand.MAIN_HAND || !player.isSneaking() || player.isSpectator()) return ActionResult.PASS;
        if (!player.getMainHandStack().isEmpty()) return ActionResult.PASS;
        if (!(entity instanceof Monster) || BLOCKED.contains(entity.getType())) return ActionResult.PASS;

        if (world.isClient) return ActionResult.SUCCESS;

        NbtCompound mob = new NbtCompound();
        if (!entity.saveSelfNbt(mob)) {
            player.sendMessage(Text.literal("Can't pick this mob up right now."), true);
            return ActionResult.FAIL;
        }
        mob.remove("UUID");

        // Head that looks like the mob (vanilla head, textured player head, or spawn egg fallback)
        ItemStack stack = MobHeads.headFor(entity.getType());

        NbtCompound root = new NbtCompound();
        root.put(KEY, mob);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        stack.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Captured ").append(entity.getName())
                .styled(s -> s.withItalic(false).withColor(Formatting.YELLOW)));
        if (entity instanceof LivingEntity living) {
            int hp = Math.round(living.getHealth());
            int max = Math.round(living.getMaxHealth());
            stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("Health: " + hp + " / " + max).styled(s -> s.withItalic(false).withColor(Formatting.GRAY)))));
        }

        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.POOF, entity.getX(), entity.getBodyY(0.5), entity.getZ(), 10, 0.3, 0.3, 0.3, 0.02);
        }
        world.playSound(null, entity.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1.0f, 1.0f);

        entity.discard();
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
        return ActionResult.SUCCESS;
    }

    // ---- place back ----------------------------------------------------------------------------

    private ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (player.isSpectator()) return ActionResult.PASS;
        ItemStack stack = player.getStackInHand(hand);
        NbtCompound data = getMobData(stack);
        if (data == null) return ActionResult.PASS;

        // Always swallow the click so the head is not placed as a block.
        if (world.isClient) return ActionResult.SUCCESS;

        BlockPos clicked = hit.getBlockPos();
        BlockState state = world.getBlockState(clicked);
        BlockPos target = state.isReplaceable() ? clicked : clicked.offset(hit.getSide());
        if (!world.canPlayerModifyAt(player, target)) return ActionResult.FAIL;

        Vec3d pos = Vec3d.ofBottomCenter(target);
        float yaw = player.getYaw() + 180.0f;
        NbtCompound copy = data.copy();
        copy.remove("UUID");

        Entity mob = EntityType.loadEntityWithPassengers(copy, world, e -> {
            e.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0.0f);
            e.setVelocity(Vec3d.ZERO);
            return e;
        });
        if (mob == null || !((ServerWorld) world).spawnNewEntityAndPassengers(mob)) {
            player.sendMessage(Text.literal("Couldn't place the mob here."), true);
            return ActionResult.FAIL;
        }

        world.emitGameEvent(player, GameEvent.ENTITY_PLACE, target);
        if (!player.getAbilities().creativeMode) stack.decrement(1);
        return ActionResult.SUCCESS;
    }

    /** Stop captured heads from being worn by right-clicking in the air. */
    private TypedActionResult<ItemStack> onUseItem(PlayerEntity player, World world, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (getMobData(stack) != null) return TypedActionResult.fail(stack);
        return TypedActionResult.pass(stack);
    }

    private static NbtCompound getMobData(ItemStack stack) {
        if (stack.isEmpty()) return null;
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return null;
        NbtCompound root = comp.copyNbt();
        if (!root.contains(KEY, NbtElement.COMPOUND_TYPE)) return null;
        return root.getCompound(KEY);
    }
}
