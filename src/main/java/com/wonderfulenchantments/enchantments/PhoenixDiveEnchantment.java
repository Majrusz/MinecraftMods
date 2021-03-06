package com.wonderfulenchantments.enchantments;

import com.mlib.config.DoubleConfig;
import com.mlib.config.IntegerConfig;
import com.wonderfulenchantments.Instances;
import com.wonderfulenchantments.RegistryHandler;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentType;
import net.minecraft.enchantment.FrostWalkerEnchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/** Enchantment that releases fire wave when entity falls. (inspired by Divinity: Original Sin 2) */
@Mod.EventBusSubscriber
public class PhoenixDiveEnchantment extends WonderfulEnchantment {
	private static final String FOOT_PARTICLE_TAG = "PhoenixDiveFootParticleTick";
	protected final DoubleConfig jumpMultiplier, damageDistance;
	protected final IntegerConfig jumpPenalty;

	public PhoenixDiveEnchantment() {
		super( "phoenix_dive", Rarity.RARE, EnchantmentType.ARMOR_FEET, EquipmentSlotType.FEET, "PheonixDive" );
		String jumpComment = "Jumping power multiplier per enchantment level.";
		String distanceComment = "Area of entities that will take damage. (area of square where A = (x - value, z - value) and B = (x + value, z + value))";
		String penaltyComment = "Penalty for using special jump. (damage to durability)";
		this.jumpMultiplier = new DoubleConfig( "jump_multiplier", jumpComment, false, 0.25, 0.01, 1.0 );
		this.damageDistance = new DoubleConfig( "damage_range", distanceComment, false, 5.0, 1.0, 100.0 );
		this.jumpPenalty = new IntegerConfig( "jump_penalty", penaltyComment, false, 3, 0, 100 );
		this.enchantmentGroup.addConfigs( this.jumpMultiplier, this.jumpPenalty, this.damageDistance );

		setMaximumEnchantmentLevel( 3 );
		setDifferenceBetweenMinimumAndMaximum( 30 );
		setMinimumEnchantabilityCalculator( level->( 10 * ( level + 1 ) ) );
	}

	@Override
	public boolean canApplyTogether( Enchantment enchantment ) {
		return !( enchantment instanceof FrostWalkerEnchantment ) && super.canApplyTogether( enchantment );
	}

	/** Event that will leave fire wave when the entity falls from certain height. */
	@SubscribeEvent
	public static void onFall( LivingFallEvent event ) {
		if( event.getDistance() <= 3.0 )
			return;

		LivingEntity attacker = event.getEntityLiving();
		int enchantmentLevel = getPhoenixDiveLevel( attacker );

		if( enchantmentLevel <= 0 || !( attacker.world instanceof ServerWorld ) )
			return;

		ServerWorld world = ( ServerWorld )attacker.world;
		for( Entity entity : getEntitiesInRange( attacker, world ) )
			if( entity instanceof LivingEntity ) {
				LivingEntity target = ( LivingEntity )entity;
				target.setFire( 3 * enchantmentLevel );
				target.attackEntityFrom( DamageSource.causeExplosionDamage( attacker ), 0 );
				target.attackEntityFrom( DamageSource.ON_FIRE, ( float )Math.sqrt( enchantmentLevel * event.getDistance() ) );
			}

		spawnFallParticles( attacker.getPositionVec(), world );
	}

	/** Event that will create particles on players that have enchantment on their boots. */
	@SubscribeEvent
	public static void onUpdate( TickEvent.PlayerTickEvent event ) {
		PlayerEntity player = event.player;
		CompoundNBT data = player.getPersistentData();
		if( getPhoenixDiveLevel( player ) <= 0 || !( player.world instanceof ServerWorld ) )
			return;

		int ticks = data.getInt( FOOT_PARTICLE_TAG );

		if( ticks % 3 == 0 )
			spawnFootParticle( player, ( ServerWorld )player.world, ticks % 6 == 0 );

		ticks++;

		if( ticks >= 6 )
			ticks = 0;

		data.putInt( FOOT_PARTICLE_TAG, ticks );
	}

	/** Event that increases jump height when player is holding sneak key. */
	@SubscribeEvent
	public static void onJump( LivingEvent.LivingJumpEvent event ) {
		if( !( event.getEntityLiving() instanceof PlayerEntity ) )
			return;

		PlayerEntity player = ( PlayerEntity )event.getEntityLiving();
		ItemStack boots = player.getItemStackFromSlot( EquipmentSlotType.FEET );
		int enchantmentLevel = getPhoenixDiveLevel( player );

		if( !player.isCrouching() || enchantmentLevel <= 0 )
			return;

		double angleInRadians = Math.toRadians( player.rotationYaw + 90.0 );
		double factor = ( enchantmentLevel + 1 ) * Instances.PHOENIX_DIVE.jumpMultiplier.get();
		player.setMotion( player.getMotion()
			.mul( new Vector3d( 0.0, 1.0 + factor, 0.0 ) )
			.add( factor * Math.cos( angleInRadians ), 0.0, factor * Math.sin( angleInRadians ) ) );

		int damagePenalty = Instances.PHOENIX_DIVE.jumpPenalty.get();
		if( damagePenalty > 0 )
			boots.damageItem( damagePenalty, player, entity->entity.sendBreakAnimation( EquipmentSlotType.FEET ) );

		if( !( player.world instanceof ServerWorld ) )
			return;

		ServerWorld world = ( ServerWorld )player.world;
		world.playSound( null, player.getPosX(), player.getPosY(), player.getPosZ(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.AMBIENT, 0.5f,
			0.9f
		);
	}

	/**
	 Returning Phoenix Dive enchantment level.

	 @param entity Entity to check level.
	 */
	protected static int getPhoenixDiveLevel( LivingEntity entity ) {
		return EnchantmentHelper.getEnchantmentLevel( Instances.PHOENIX_DIVE, entity.getItemStackFromSlot( EquipmentSlotType.FEET ) );
	}

	/**
	 Getting entities in certain range.

	 @param entity Entity as a start position.
	 @param world  Current entity world.

	 @return Returns list with entities that were in range.
	 */
	protected static List< Entity > getEntitiesInRange( LivingEntity entity, ServerWorld world ) {
		double range = Instances.PHOENIX_DIVE.damageDistance.get();
		return world.getEntitiesWithinAABBExcludingEntity( entity.getEntity(), entity.getBoundingBox()
			.offset( -range, -entity.getHeight() * 0.5D, -range )
			.expand( range * 2.0D, 0, range * 2.0D ) );
	}

	/**
	 Spawning particles on fall.

	 @param position Position where the entity landed.
	 @param world    World where particles should be spawned.
	 */
	protected static void spawnFallParticles( Vector3d position, ServerWorld world ) {
		double x = position.getX(), y = position.getY(), z = position.getZ();
		for( double d = 0.0; d < 3.0; d++ )
			world.spawnParticle( RegistryHandler.PHOENIX_PARTICLE.get(), x, y, z, ( int )Math.pow( 5.0, d + 1.0 ), 0.0625, 0.125, 0.0625,
				( 0.125 + 0.0625 ) * ( d + 1.0 )
			);

		world.playSound( null, x, y, z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.AMBIENT, 0.5f, 0.9f );
	}

	/**
	 Spawning particles at foot height.

	 @param entity    Entity where the particles will be spawned.
	 @param world     World where particles should be spawned.
	 @param isLeftLeg Flag that informs to spawn particle at left leg position.
	 */
	protected static void spawnFootParticle( LivingEntity entity, ServerWorld world, boolean isLeftLeg ) {
		if( entity.isElytraFlying() )
			return;

		double leftLegRotation = ( isLeftLeg ? 180.0 : 0.0 );
		double angleInRadians = Math.toRadians( entity.rotationYaw + 90.0 + leftLegRotation );
		world.spawnParticle( ParticleTypes.FLAME, entity.getPosX() + 0.1875 * Math.sin( -angleInRadians ), entity.getPosY() + 0.1,
			entity.getPosZ() + 0.1875 * Math.cos( -angleInRadians ), 1, 0.0, 0.125 * Math.cos( angleInRadians ), 0.00, 0.0
		);
	}
}
