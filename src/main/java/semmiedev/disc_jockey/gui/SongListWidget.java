package semmiedev.disc_jockey.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Song;
import semmiedev.disc_jockey.SongLoader;
import semmiedev.disc_jockey.SongLoader.SongFolder;
import semmiedev.disc_jockey.Util;
import semmiedev.disc_jockey.gui.screen.DiscJockeyScreen;
import semmiedev.disc_jockey.mixin.EntryListWidgetAccessor;

public class SongListWidget extends AbstractSelectionList<SongListWidget.Entry> {
    private static final String FOLDER_EMOJI = "📁";
    private static final int LEFT_PADDING = 10;

    public static abstract class Entry extends AbstractSelectionList.Entry<Entry> {
        public abstract boolean isSelected();
        public abstract void setSelected(boolean selected);
        public abstract boolean isSongEntry();
    }

    @Nullable
    public SongEntry getSelectedSongOrNull() {
        Entry selected = getSelected();
        return selected instanceof SongEntry ? (SongEntry) selected : null;
    }

    @Nullable
    public FolderEntry getSelectedFolderOrNull() {
        Entry selected = getSelected();
        return selected instanceof FolderEntry ? (FolderEntry) selected : null;
    }

    private final Screen parentScreen;

    public SongListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
        super(client, width, height, top, itemHeight);
        this.parentScreen = null;
    }

    public SongListWidget(Minecraft client, int width, int height, int top, int itemHeight, Screen parentScreen) {
        super(client, width, height, top, itemHeight);
        this.parentScreen = parentScreen;
    }

    public Screen getParentScreen() {
        return parentScreen;
    }

    public void safeClearEntries() {
        this.clearEntries();
    }

    public void safeReplaceEntries(java.util.Collection<SongListWidget.Entry> entries) {
        this.replaceEntries(entries);
    }

    @SuppressWarnings("unchecked")
    public java.util.List<SongListWidget.Entry> getModifiableChildren() {
        return (java.util.List<SongListWidget.Entry>) ((EntryListWidgetAccessor) this).getChildrenList();
    }

    public int getItemHeight() {
        return this.defaultEntryHeight;
    }

    @Override
    public int getRowLeft() {
        return super.getRowLeft() + LEFT_PADDING;
    }

    @Override
    public int getRowWidth() {
        return width - 40;
    }

    @Override
    protected int scrollBarX() {
        return getX() + width - 12;
    }

    @Override
    public void setSelected(@Nullable Entry entry) {
        Entry selectedEntry = getSelected();
        if (selectedEntry != null) selectedEntry.setSelected(false);
        if (entry != null) entry.setSelected(true);
        super.setSelected(entry);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        // Who cares
    }

    public static class SongEntry extends Entry {
        private static final Identifier ICONS = Identifier.fromNamespaceAndPath(Main.MOD_ID, "textures/gui/icons.png");

        public final int index;
        public final Song song;

        public boolean selected, favorite;
        public SongListWidget songListWidget;
        private long lastClickedAt = Util.TIMESTAMP_UNINITIALIZED;

        private final Minecraft client = Minecraft.getInstance();

        public SongEntry(Song song, int index) {
            this.song = song;
            this.index = index;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            int x = this.getX();
            int y = this.getY();
            int entryWidth = this.getWidth();
            int entryHeight = this.getHeight();

            if (selected) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0x000000);
            }

            context.text(client.font, song.displayName, x + 18, y + 5, selected ? 0xFFFFFFFF : 0xFF808080);

            context.blit(RenderPipelines.GUI_TEXTURED, ICONS, x + 2, y + 2, (favorite ? 26 : 0) + (isOverFavoriteButton(mouseX, mouseY) ? 13 : 0), 0, 13, 12, 52, 12);
        }

        public Component getNarrateText() {
            return Component.literal(song.displayName);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            double mouseX = click.x();
            double mouseY = click.y();
            int button = click.button();

            if (isOverFavoriteButton(mouseX, mouseY)) {
                favorite = !favorite;
                if (favorite) {
                    Main.config.favorites.add(song.fileName);
                } else {
                    Main.config.favorites.remove(song.fileName);
                }
                return true;
            }

            if (songListWidget.getSelected() == this && lastClickedAt != -1L && Util.now() - lastClickedAt <= 350) {
                Main.SONG_PLAYER.start(this.song);
            } else {
                songListWidget.setSelected(this);
                Main.config.lastSelectedSong = song.filePath;
                lastClickedAt = Util.now();
            }
            return true;
        }

        private boolean isOverFavoriteButton(double mouseX, double mouseY) {
            int x = this.getX();
            int y = this.getY();

            return mouseX > x + 2 && mouseX < x + 15 && mouseY > y + 2 && mouseY < y + 14;
        }

        @Override
        public boolean isSelected() {
            return selected;
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public boolean isSongEntry() {
            return true;
        }
    }

    public static class FolderEntry extends Entry {
        public SongFolder folder;
        public boolean selected;
        public SongListWidget songListWidget;
        public String displayName;

        private final Minecraft client = Minecraft.getInstance();

        public FolderEntry(@Nullable SongFolder folder, SongListWidget songListWidget) {
            this.folder = folder;
            this.songListWidget = songListWidget;
            this.displayName = folder != null ? folder.name : "..";
        }

        @Override
        public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            int x = this.getX();
            int y = this.getY();
            int entryWidth = this.getWidth();
            int entryHeight = this.getHeight();

            if (selected) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0x000000);
            }

            String displayText = FOLDER_EMOJI + " " + displayName;
            if (client.font.width(displayText) > entryWidth - 8) {
                while (client.font.width(displayText + "...") > entryWidth - 8 && displayText.length() > 3) {
                    displayText = displayText.substring(0, displayText.length() - 1);
                }
                displayText = displayText + "...";
            }
            context.text(client.font, displayText, x + 6, y + 5, selected ? 0xFFFFFFFF : 0xFF808080);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            double mouseX = click.x();
            double mouseY = click.y();
            int button = click.button();

            if (button == 0) {
                if (songListWidget.getParentScreen() instanceof DiscJockeyScreen screen) {
                    if (this.folder == null) {
                        SongFolder parent = screen.findParentFolder(screen.currentFolder);
                        if (parent != null || SongLoader.FOLDERS.contains(screen.currentFolder)) {
                            screen.currentFolder = parent;
                            screen.shouldFilter = true;
                        }
                    } else {
                        screen.currentFolder = this.folder;
                        screen.shouldFilter = true;
                    }
                    songListWidget.setSelected(this);
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isSelected() {
            return selected;
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public boolean isSongEntry() {
            return false;
        }
    }
}