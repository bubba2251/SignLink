package com.bergerkiller.bukkit.sl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import com.bergerkiller.bukkit.common.BlockLocation;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.utils.BlockUtil;
import com.bergerkiller.bukkit.common.utils.FaceUtil;
import com.bergerkiller.bukkit.common.utils.MaterialUtil;
import com.bergerkiller.bukkit.common.utils.StringUtil;

public class LinkedSign {
	public BlockLocation location;
	public int line;
	private boolean updateSignOrder = false;
	public SignDirection direction;
	private String oldtext;
	private final ArrayList<VirtualSign> displaySigns = new ArrayList<VirtualSign>();
	private static HashSet<IntVector3> loopCheck = new HashSet<IntVector3>(); // Used to prevent server freeze when finding signs

	public LinkedSign(BlockLocation location, int lineAt, SignDirection direction) {
		this.location = location;
		this.line = lineAt;
		this.direction = direction;
	}

	public LinkedSign(String worldname, int x, int y, int z, int lineAt, SignDirection direction) {
		this(new BlockLocation(worldname, x, y, z), lineAt, direction);
	}

	public LinkedSign(Block from, int line) {
		this(new BlockLocation(from), line, SignDirection.NONE);
		if (MaterialUtil.ISSIGN.get(from)) {
			VirtualSign sign = VirtualSign.get(from);
			String text = sign.getRealLine(line);
			int peri = text.indexOf("%");
			if (peri != -1 && text.lastIndexOf("%") == peri) {
				//get direction from text
				if (peri == 0) {
					this.direction = SignDirection.RIGHT;
				} else if (peri == text.length() - 1) {
					this.direction = SignDirection.LEFT;
				} else if (text.substring(peri).contains(" ")) {
					this.direction = SignDirection.LEFT;
				} else {
					this.direction = SignDirection.RIGHT;
				}
			}
		}
	}

	public void updateText(String... forplayers){
		setText(this.oldtext, forplayers);
	}

	/**
	 * Gets the full line of text this LinkedSign currently displays
	 * 
	 * @return Line of text
	 */
	public String getText() {
		return this.oldtext;
	}

	public void setText(String value, String... forplayers) {	
		oldtext = value;
		if (!SignLink.updateSigns) {
			return; 
		}
		final ArrayList<VirtualSign> signs = getSigns();
		if (signs.isEmpty()) {
			return;
		}

		//Get the start offset
		String startline = signs.get(0).getRealLine(this.line);
		int startoffset = startline.indexOf("%");
		if (startoffset == -1) {
			startoffset = 0;
		}
		int maxlength = 15 - startoffset;

		//Get the color of the text before this variable
		ChatColor color = ChatColor.BLACK;
		for (int i = 0; i < startoffset; i++) {
			if (startline.charAt(i) == StringUtil.CHAT_STYLE_CHAR) {
				i++;
				color = StringUtil.getColor(startline.charAt(i), color);
			}
		}

		ArrayList<String> bits = new ArrayList<String>();
		ChatColor prevcolor = color;
		StringBuilder lastbit = new StringBuilder(16);
		// Fix up colors in text because of text being cut-off
		// Appends a color in the right positions
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == StringUtil.CHAT_STYLE_CHAR) {
				if (i < value.length() - 1) {
					i++;
					color = StringUtil.getColor(value.charAt(i), color);
				}
			} else {
				// Handle a change of color
				if (prevcolor != color) {
					if (lastbit.length() < maxlength - 2) {
						// Room to append a color?
						lastbit.append(color);
					} else if (lastbit.length() == maxlength - 2) {
						// Lesser, color is allowed, but not an additional character
						bits.add(lastbit.toString() + color);
						// Prepare for the next full text
						maxlength = 15;
						lastbit.setLength(0);
						if (color != ChatColor.BLACK) {
							lastbit.append(color);
						}
					} else {
						//Greater, color is not allowed
						bits.add(lastbit.toString());
						// Prepare for the next full text
						maxlength = 15;
						lastbit.setLength(0);
						if (color != ChatColor.BLACK) {
							lastbit.append(color);
						}
					}
				}
				lastbit.append(c);
				prevcolor = color;
				if (lastbit.length() == maxlength) {
					bits.add(lastbit.toString());
					// Prepare for the next full text
					maxlength = 15;
					lastbit.setLength(0);
					if (color != ChatColor.BLACK) {
						lastbit.append(color);
					}
				}
			}
		}
		// Add a remaining bit
		bits.add(lastbit + StringUtil.getFilledString(" ", maxlength - lastbit.length()));

		//Set the signs
		int index = 0;
		for (VirtualSign sign : signs) {
			if (index == bits.size()) {
				//clear the sign
				sign.setLine(this.line, "", forplayers);
			} else {
				String line = sign.getRealLine(this.line);
				if (index == 0 && signs.size() == 1) {
					//set the value in between the two % %
					String start = line.substring(0, startoffset);
					int endindex = line.lastIndexOf("%");
					if (endindex != -1 && endindex != startoffset) {
						String end = line.substring(endindex + 1);
						line = start + bits.get(0);
						int remainder = 15 - line.length() - end.length();
						if (remainder < 0) {
							line = line.substring(0, line.length() + remainder);
						}
						line += end;
					} else {
						line = start + bits.get(0);
					}
				} else if (index == 0) {
					//first, take % in account
					String bit = bits.get(0);
					line = line.substring(0, startoffset) + bit;
				} else if (index == signs.size() - 1) {
					//last, take % in account
					String bit = bits.get(index);
					int endindex = line.lastIndexOf("%") + 1;
					if (endindex > line.length() - 1) {
						endindex = line.length() - 1;
					}
					String end = "";
					if (endindex < line.length() - 1) {
						end = line.substring(endindex);
					}
					endindex = 15 - end.length();
					if (endindex > bit.length() - 1) {
						endindex = bit.length() - 1;
					}
					line = bit.substring(0, endindex) + end;
				} else {
					//A sign in the middle, simply set it
					line = bits.get(index);
				}
				sign.setLine(this.line, line, forplayers);
				index++;
			}
		}
	}

	public void update() {
		ArrayList<VirtualSign> signs = getSigns();
		if (!signs.isEmpty()) {
			for (VirtualSign sign : signs) {
				sign.update();
			}
		}
	}

	/**
	 * Gets the starting Block, the first sign block this Linked Sign shows text on
	 * 
	 * @return Linked sign starting block
	 */
	public Block getStartBlock() {
		if (this.location.isLoaded()) {
			return this.location.getBlock();
		} else {
			return null;
		}
	}

	/**
	 * Gets the location of the block where this linked sign starts
	 * 
	 * @return Linked sign start location
	 */
	public Location getStartLocation() {
		Block b = getStartBlock();
		return b == null ? null : b.getLocation();
	}

	/**
	 * Tells this Linked Sign to update the order of the signs, to update how text is divided
	 */
	public void updateSignOrder() {
		this.updateSignOrder = true;
	}

	/**
	 * Gets the signs which this Linked Sign displays text on, validates the signs
	 * 
	 * @return The virtual signs on which this Linked Sign shows text
	 */
	public ArrayList<VirtualSign> getSigns() {
		return this.getSigns(true);
	}

	private boolean validateSigns() {
		if (!this.displaySigns.isEmpty()) {
			for (VirtualSign sign : this.displaySigns) {
				if (!sign.isValid()) {
					sign.remove();
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private Block nextSign(Block from) {
		BlockFace face = BlockUtil.getFacing(from);
		face = FaceUtil.rotate(face, 2);
		if (this.direction == SignDirection.RIGHT) {
			face = face.getOppositeFace();
		}
		Block next = from.getRelative(face);
		Block rval = next;
		if (!MaterialUtil.ISSIGN.get(next)) {
			rval = null;
			//Jumping a gap?
			for (BlockFace f : FaceUtil.ATTACHEDFACESDOWN) {
				Block next2 = next.getRelative(f);
				if (MaterialUtil.ISSIGN.get(next2)) {
					next = next2;
					rval = next;
					break;
				}
			}
		}
		if (rval == null || !loopCheck.add(new IntVector3(rval)))  {
			return null;
		}
		return rval;
	}

	/**
	 * Gets the signs which this Linked Sign displays text on
	 * 
	 * @param validate the signs, True to validate that all signs exist, False to ignore that check
	 * @return The virtual signs on which this Linked Sign shows text
	 */
	public ArrayList<VirtualSign> getSigns(boolean validate) {
		if (!validate) {
			return this.displaySigns;
		}
		Block start = getStartBlock();
		//Unloaded chunk?
		if (start == null) {
			this.displaySigns.clear();
			return this.displaySigns;
		}

		if (validateSigns() && !this.updateSignOrder) {
			return displaySigns;
		}
		this.updateSignOrder = false;

		//Regenerate old signs and return
		this.displaySigns.clear();
		if (MaterialUtil.ISSIGN.get(start)) {
			loopCheck.clear();
			this.displaySigns.add(VirtualSign.get(start));
			if (this.direction == SignDirection.NONE) {
				return displaySigns;
			}
			while (start != null) {
				//Check for next signs
				start = nextSign(start);
				if (start != null) {
					VirtualSign sign = VirtualSign.get(start);
					String realline = sign.getRealLine(this.line);
					int index = realline.indexOf('%');
					if (index != -1) {
						//allow?
						if (index == 0 && index == realline.length() - 1) {
							//the only char on the sign - allowed
						} else if (index == 0) {
							//all left - space to the right?
							if (realline.charAt(index + 1) != ' ') break;
						} else if (index == realline.length() - 1) {
							//all right - space to the left?
							if (realline.charAt(index - 1) != ' ') break;
						} else {
							//centered - surrounded by spaces?
							if (realline.charAt(index - 1) != ' ') break;
							if (realline.charAt(index + 1) != ' ') break;
						}
						start = null;
					}
					this.displaySigns.add(sign);
				} else {
					break;
				}
			}
			if (this.direction == SignDirection.LEFT) {
				Collections.reverse(this.displaySigns);
			}
		}
		return this.displaySigns;
	}
}
