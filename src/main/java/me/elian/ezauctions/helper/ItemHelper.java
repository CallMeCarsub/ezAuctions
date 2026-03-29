package me.elian.ezauctions.helper;

import com.destroystokyo.paper.MaterialTags;
import com.google.gson.JsonParser;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.JukeboxPlayable;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.UnaryOperator;

public class ItemHelper {
	// Memoize reflection operations
	// Valid because both methods are Server API so
	// the values will not change during runtime.
	private static Optional<Boolean> hasAsHoverEvent = Optional.empty();
	private static Optional<Boolean> hasItemMetaAsString = Optional.empty();

	public static byte[] serialize(@NotNull ItemStack item) throws IllegalStateException {
		try (var outputStream = new ByteArrayOutputStream();
		     var dataOutput = new BukkitObjectOutputStream(outputStream)) {
			dataOutput.writeObject(item);
			dataOutput.flush();
			return Base64.getEncoder().encode(outputStream.toByteArray());
		} catch (Exception e) {
			throw new IllegalStateException("Unable to save item stacks.", e);
		}
	}

	public static @NotNull ItemStack deserialize(byte[] data) throws IOException {
		try (var inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
		     var dataInput = new BukkitObjectInputStream(inputStream)) {
			return (ItemStack) dataInput.readObject();
		} catch (ClassNotFoundException e) {
			throw new IOException("Unable to decode class type.", e);
		}
	}

	public static boolean addItemToPlayerInventory(@NotNull Player player, @NotNull ItemStack itemStack, int amount) {
		ArrayList<ItemStack> items = new ArrayList<>();
		int maxStackSize = itemStack.getMaxStackSize();
		while (amount > maxStackSize) {
			ItemStack clone = itemStack.clone();
			clone.setAmount(maxStackSize);
			items.add(clone);
			amount -= maxStackSize;
		}

		if (amount != 0) {
			ItemStack clone = itemStack.clone();
			clone.setAmount(amount);
			items.add(clone);
		}

		ItemStack[] array = new ItemStack[items.size()];
		array = items.toArray(array);

		HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(array);

		if (leftover.isEmpty())
			return false;

		Location location = player.getLocation();
		World world = player.getWorld();
		for (ItemStack item : leftover.values()) {
			world.dropItem(location, item);
		}

		return true;
	}

	public static void removeItemFromPlayerInventory(@NotNull Player player, @NotNull ItemStack itemStack,
	                                                 int amount) {
		int remainingAmount = amount;
		PlayerInventory inventory = player.getInventory();

		ItemStack mainHand = inventory.getItemInMainHand();

		if (mainHand.isSimilar(itemStack)) {
			int itemAmount = mainHand.getAmount();
			if (itemAmount <= remainingAmount) {
				inventory.setItemInMainHand(null);
				remainingAmount -= itemAmount;
			} else {
				mainHand.setAmount(itemAmount - remainingAmount);
				inventory.setItemInMainHand(mainHand);
				return;
			}
		}

		for (int i = 0; i < inventory.getSize(); i++) {
			ItemStack is = inventory.getItem(i);

			if (is == null || !itemStack.isSimilar(is))
				continue;

			if (is.getAmount() > remainingAmount) {
				is.setAmount(is.getAmount() - remainingAmount);
				inventory.setItem(i, is);

				break;
			}

			remainingAmount -= is.getAmount();
			inventory.setItem(i, null);

			if (remainingAmount == 0)
				break;
		}
	}

	public static int getAmountOfItemInInventory(@NotNull Player player, @NotNull ItemStack itemStack) {
		int amountInInventory = 0;

		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getSize(); i++) {
			ItemStack is = inventory.getItem(i);

			if (is == null || !itemStack.isSimilar(is))
				continue;

			amountInInventory += is.getAmount();
		}

		return amountInInventory;
	}

	private static boolean hasAsHoverEventMethod() {
		if (hasAsHoverEvent.isPresent()) {
			return hasAsHoverEvent.get();
		}

		Boolean methodExists = Boolean.FALSE;
		try {
			// Check if ItemStack has asHoverEvent method exists
			ItemStack.class.getMethod("asHoverEvent", UnaryOperator.class);
			methodExists = Boolean.TRUE;
		} catch (NoSuchMethodException ignored) {
		}
		hasAsHoverEvent = Optional.of(methodExists);
		return methodExists;
	}

	@Nullable
	public static HoverEvent<HoverEvent.ShowItem> getItemHover(@NotNull ItemStack itemStack,
	                                                           UnaryOperator<HoverEvent.ShowItem> transform) {
		// Supported by PaperMC (and forks) servers
		if (hasAsHoverEventMethod()) {
			return itemStack.asHoverEvent(transform);
		}

		// Try to get NBT
		String itemNBT = null;
		try {
			itemNBT = getItemNBT(itemStack).replace("minecraft:", "");
		} catch (Exception ignored) {
		}

		if (itemNBT == null) {
			return null;
		}

		NamespacedKey typeKey = itemStack.getType().getKey();
		Key itemKey = Key.key(typeKey.getNamespace(), typeKey.getKey());
		return HoverEvent.showItem(itemKey, itemStack.getAmount(),
						BinaryTagHolder.binaryTagHolder(itemNBT))
				.asHoverEvent(transform);
	}

	private static boolean hasItemMetaGetAsStringMethod() {
		if (hasItemMetaAsString.isPresent()) {
			return hasItemMetaAsString.get();
		}

		Boolean methodExists = Boolean.FALSE;
		try {
			// Check if ItemStack has asHoverEvent method exists
			ItemMeta.class.getMethod("getAsString");
			methodExists = Boolean.TRUE;
		} catch (NoSuchMethodException ignored) {
		}
		hasItemMetaAsString = Optional.of(methodExists);
		return methodExists;
	}

	public static @NotNull String getItemNBT(@NotNull ItemStack itemStack)
			throws Exception {
		// Supported by Spigot 1.18+
		if (hasItemMetaGetAsStringMethod()) {
			return itemStack.hasItemMeta() ? itemStack.getItemMeta().getAsString() : "{}";
		}

		return getItemNbtNms(itemStack);
	}

	private static @NotNull String getItemNbtNms(@NotNull ItemStack itemStack) throws NoSuchMethodException,
			InvocationTargetException, IllegalAccessException, ClassNotFoundException, InstantiationException {
		Class<? extends ItemStack> itemStackClass = itemStack.getClass();
		Class<?> nbtTagCompoundClass = Class.forName("net.minecraft.nbt.NBTTagCompound");

		// get the nms copy
		Object nmsStack = itemStackClass.getMethod("asNMSCopy", ItemStack.class).invoke(null, itemStack);

		// find the save method from the nms stack
		Method getNbtMethod = null;
		for (Method method : nmsStack.getClass().getMethods()) {
			if (method.getReturnType().equals(nbtTagCompoundClass) && method.getParameterCount() == 0) {
				getNbtMethod = method;
				break;
			}
		}

		if (getNbtMethod == null)
			throw new NoSuchMethodException("Could not save item to nbt! getNbtMethod not found!");

		// get item tag
		Object tag = getNbtMethod.invoke(nmsStack);
		// if no tag, create empty tag
		if (tag == null) {
			tag = nbtTagCompoundClass.getConstructor().newInstance();
		}
		// get string form of nbttagcompound
		return tag.toString();
	}

	private static NamespacedKey CANVAS_ID_KEY = NamespacedKey.fromString("xercapaint:canvas_id");
	private static NamespacedKey CANVAS_TITLE_KEY = NamespacedKey.fromString("xercapaint:canvas_title");
	private static NamespacedKey CANVAS_AUTHOR_KEY = NamespacedKey.fromString("xercapaint:canvas_author");

	private static NamespacedKey PICTURE_DATA_KEY = NamespacedKey.fromString("camerapture:picture_data");

	private static String escapeMiniMessage(String input){
		return MiniMessage.miniMessage().escapeTags(input);
	}
	public static @NotNull String getMinecraftName(ItemStack is) {
		Material material = is.getType();
		if(MaterialTags.MUSIC_DISCS.isTagged(material)){
			JukeboxPlayable playable = is.getData(DataComponentTypes.JUKEBOX_PLAYABLE);
			if(playable != null){
				JukeboxSong song = playable.jukeboxSong();
				return MiniMessage.miniMessage().serialize(song.getDescription());
			}
		}

		if(is.getPersistentDataContainer().has(CANVAS_TITLE_KEY)){
			String title = is.getPersistentDataContainer().getOrDefault(CANVAS_TITLE_KEY, PersistentDataType.STRING, "Unnamed Painting");
			String author = is.getPersistentDataContainer().getOrDefault(CANVAS_AUTHOR_KEY, PersistentDataType.STRING, "Unknown Artist");
			return "\"" + escapeMiniMessage(title) + "\" by " + author;
		}else if(is.getPersistentDataContainer().has(CANVAS_ID_KEY)){
			return "Unfinished Painting";
		}else if(is.getPersistentDataContainer().has(PICTURE_DATA_KEY)){
			try {
				return "Picture taken by " +
						JsonParser.parseString(is.getPersistentDataContainer().getOrDefault(PICTURE_DATA_KEY, PersistentDataType.STRING,"{\"creator\":\"UNKNOWN?\"}"))
								.getAsJsonObject().get("creator").getAsString();
			}catch (Exception e){
				return "Picture (? Error)";
			}
		}

		if(is.hasData(DataComponentTypes.ITEM_NAME)){
			return MiniMessage.miniMessage().serialize(is.getData(DataComponentTypes.ITEM_NAME));
		}
		return "<lang:" + is.translationKey() + ">";
	}

	public static String getSprite(ItemStack stack){
		String spritePiece;
		if(stack.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
			spritePiece = "<sprite:gui:icon/chat_modified>";
		}else if(stack.getType().isBlock()){
			spritePiece = "<sprite:blocks:block/" + stack.getType().getKey().value() + ">";
		}else{
			if(stack.getType() == Material.DEBUG_STICK){
				spritePiece = "<sprite:items:\"smponline:item/debug_stick\">";
			}else {
				spritePiece = "<sprite:items:item/" + stack.getType().getKey().value() + ">";
			}
		}
		return "<white>" + spritePiece + "</white> ";
	}

	/**
	 * @return 0 if the item has never been repaired or -1 if it is no longer repairable.
	 */
	public static int getXPForRepair(ItemStack is) {
		int cost = (int) is.getItemMeta().serialize().getOrDefault("repair-cost", 0);
		boolean repairable = cost <= 40;
		return repairable ? cost : -1;
	}
}
