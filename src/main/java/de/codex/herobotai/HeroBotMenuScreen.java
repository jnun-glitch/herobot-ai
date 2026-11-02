package de.codex.herobotai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

/** Polished client-side dashboard opened by /hba or /hba kit gui. */
public final class HeroBotMenuScreen extends Screen {
    public static final int HOME_PAGE = 0;
    public static final int KITS_PAGE = 1;

    private static final int PANEL = 0xF20B111D;
    private static final int PANEL_LIGHT = 0xFF121C2A;
    private static final int ACCENT = 0xFF58D6C4;
    private static final int MUTED = 0xFF9AAABD;
    private static final List<KitCardData> KIT_CARDS = List.of(
            new KitCardData("iron", "Iron", "Schwert · Schild · Eisenrüstung", 0xFFB9C5D3, new ItemStack(Items.IRON_SWORD)),
            new KitCardData("diamond", "Diamond", "Schwert · Axt · Diamantrüstung", 0xFF64DCEB, new ItemStack(Items.DIAMOND_SWORD)),
            new KitCardData("axe", "Axe", "Axt-Loadout für Schild-Duelle", 0xFFE8B16A, new ItemStack(Items.IRON_AXE)),
            new KitCardData("netherite", "Netherite", "Netheritschwert und Rüstung", 0xFFB089C8, new ItemStack(Items.NETHERITE_SWORD)),
            new KitCardData("mace", "Mace + Wind Charge", "Wind Charge · Density V · Wind Burst III", 0xFFFF8C69, new ItemStack(Items.MACE)),
            new KitCardData("crystal", "Crystal PvP", "Totem · Obsidian · Endkristalle", 0xFFDB72FF, new ItemStack(Items.END_CRYSTAL)),
            new KitCardData("fc_axe", "FC Axe", "FireClient-Axtvorlage", 0xFFE6A66E, new ItemStack(Items.IRON_AXE)),
            new KitCardData("fc_uhc", "FC UHC", "FireClient-UHC-Vorlage", 0xFF75D28D, new ItemStack(Items.GOLDEN_APPLE)),
            new KitCardData("fc_misch", "FC Misch", "Schwert · Axt · Totem", 0xFF8FA9FF, new ItemStack(Items.TOTEM_OF_UNDYING)));

    private final int page;
    private final Screen parent;
    private String selectedKit = "crystal";
    private String rememberedRecipient = "";
    private TextFieldWidget recipientField;

    public HeroBotMenuScreen(int page, Screen parent) {
        super(Text.literal(page == KITS_PAGE ? "HeroBot · Kits" : "HeroBot · Control Center"));
        this.page = page;
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (this.page == KITS_PAGE) {
            initKitPage();
        } else {
            initHomePage();
        }
    }

    private void initHomePage() {
        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int bottom = top + panelHeight();
        int cardGap = 12;
        int cardWidth = (panelWidth - 48 - cardGap) / 2;
        int rowGap = 8;
        int startY = top + 68;
        int footerTop = bottom - (panelHeight() >= 300 ? 77 : 10);
        int cardHeight = Math.min(68, Math.max(16, (footerTop - startY - rowGap * 2) / 3));
        int startX = left + 24;
        int rowStep = cardHeight + rowGap;

        addCard(startX, startY, cardWidth, cardHeight, "TRAINING STARTEN",
                "Direktduell · eigenes Inventar kopieren", 0xFF58D6C4,
                () -> runCommand("hba spawn"), new ItemStack(Items.IRON_SWORD));
        addCard(startX + cardWidth + cardGap, startY, cardWidth, cardHeight, "KITS & LOADOUTS",
                "Ausrüstung auswählen und bestätigen", 0xFFDB72FF,
                () -> MinecraftClient.getInstance().setScreen(new HeroBotMenuScreen(KITS_PAGE, this)),
                new ItemStack(Items.CHEST));
        addCard(startX, startY + rowStep, cardWidth, cardHeight, "STATUS",
                "Training, Kit und Lernfortschritt", 0xFF64DCEB,
                () -> runCommand("hba status"), new ItemStack(Items.CLOCK));
        addCard(startX + cardWidth + cardGap, startY + rowStep, cardWidth, cardHeight, "TRAINING STOPPEN",
                "Kampfsteuerung sicher anhalten", 0xFFE8B16A,
                () -> runCommand("hba stop"), new ItemStack(Items.SHIELD));
        addCard(startX, startY + rowStep * 2, cardWidth, cardHeight, "BOT ENTFERNEN",
                "Aktiven Trainingsbot entfernen", 0xFFFF7777,
                () -> runCommand("hba remove"), new ItemStack(Items.BARRIER));
        addCard(startX + cardWidth + cardGap, startY + rowStep * 2, cardWidth, cardHeight, "HILFE",
                "Befehle und kurze Anleitung anzeigen", 0xFF8FA9FF,
                () -> runCommand("hba help"), new ItemStack(Items.BOOK));
    }

    private void initKitPage() {
        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        boolean compact = this.width < 760 || panelHeight < 360;
        int columns = 3;
        int sidebarWidth = compact ? 0 : 224;
        int gapX = compact ? 8 : 9;
        int gridX = left + 24;
        int gridWidth = panelWidth - 48 - (compact ? 0 : sidebarWidth + 14);
        int cardWidth = (gridWidth - gapX * (columns - 1)) / columns;
        int gapY = compact ? 6 : 8;
        int gridTop = top + (compact ? 69 : 78);
        int rowCount = (KIT_CARDS.size() + columns - 1) / columns;
        int footerStatusTop = top + panelHeight - 57;
        int gridHeight = footerStatusTop - gridTop - 6;
        int fittedCardHeight = (gridHeight - gapY * (rowCount - 1)) / rowCount;
        int cardHeight = Math.max(18, Math.min(compact ? 31 : 43, fittedCardHeight));

        for (int index = 0; index < KIT_CARDS.size(); index++) {
            KitCardData kit = KIT_CARDS.get(index);
            int x = gridX + (index % columns) * (cardWidth + gapX);
            int y = gridTop + (index / columns) * (cardHeight + gapY);
            boolean selected = kit.id().equals(this.selectedKit);
            addCard(x, y, cardWidth, cardHeight, kit.title(),
                    compact ? kit.id().toUpperCase() : kit.subtitle(), kit.color(),
                    () -> {
                        this.selectedKit = kit.id();
                        this.rememberedRecipient = this.recipientField == null
                                ? this.rememberedRecipient : this.recipientField.getText();
                        this.clearAndInit();
                    }, selected, kit.icon());
        }

        KitCardData selected = selectedKitData();
        if (compact) {
            drawFooterStatus = selected.title() + " selected · " + selected.subtitle();
        } else {
            int sideX = gridX + gridWidth + 14;
            int sideY = top + 78;
            int sideHeight = panelHeight() - 132;
            this.addDrawableChild(new SidePanel(sideX, sideY, sidebarWidth, sideHeight, selected));
            recipientField = new TextFieldWidget(this.textRenderer, sideX + 12, sideY + sideHeight - 48,
                    sidebarWidth - 24, 20, Text.literal("Kit-Empfänger"));
            recipientField.setMaxLength(16);
            recipientField.setPlaceholder(Text.literal("Spielername (optional)"));
            recipientField.setText(this.rememberedRecipient);
            recipientField.setChangedListener(value -> this.rememberedRecipient = value);
            this.addDrawableChild(recipientField);
            drawFooterStatus = selected.subtitle();
        }

        int footerY = top + panelHeight() - 34;
        int buttonGap = compact ? 5 : 8;
        int buttonWidth = (panelWidth - 48 - buttonGap * 2) / 3;
        int buttonX = left + 24;
        addCard(buttonX, footerY, buttonWidth, 23, "ZURÜCK", "", 0xFF8293A8,
                () -> MinecraftClient.getInstance().setScreen(this.parent));
        addCard(buttonX + buttonWidth + buttonGap, footerY, buttonWidth, 23, "KIT GEBEN", "", selected.color(),
                () -> openConfirmation(false));
        addCard(buttonX + (buttonWidth + buttonGap) * 2, footerY, buttonWidth, 23,
                "BOT STARTEN", "", ACCENT, () -> openConfirmation(true));
    }

    private String drawFooterStatus = "";

    private void openConfirmation(boolean startBot) {
        if (recipientField != null) {
            rememberedRecipient = recipientField.getText().trim();
        }
        MinecraftClient.getInstance().setScreen(
                new ConfirmKitScreen(this, selectedKitData(), rememberedRecipient, startBot));
    }

    private KitCardData selectedKitData() {
        return KIT_CARDS.stream().filter(kit -> kit.id().equals(this.selectedKit))
                .findFirst().orElse(KIT_CARDS.get(0));
    }

    private void addCard(int x, int y, int width, int height, String title, String subtitle,
                         int color, Runnable action) {
        addCard(x, y, width, height, title, subtitle, color, action, false, ItemStack.EMPTY);
    }

    private void addCard(int x, int y, int width, int height, String title, String subtitle,
                         int color, Runnable action, boolean selected) {
        addCard(x, y, width, height, title, subtitle, color, action, selected, ItemStack.EMPTY);
    }

    private void addCard(int x, int y, int width, int height, String title, String subtitle,
                         int color, Runnable action, ItemStack icon) {
        addCard(x, y, width, height, title, subtitle, color, action, false, icon);
    }

    private void addCard(int x, int y, int width, int height, String title, String subtitle,
                         int color, Runnable action, boolean selected, ItemStack icon) {
        this.addDrawableChild(new ActionCard(x, y, width, height, title, subtitle, color,
                action, selected, icon));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        drawBackdrop(context);
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + panelHeight();
        context.fill(left - 3, top - 3, right + 3, bottom + 3, 0xA0000000);
        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF26374A);
        context.fill(left, top, right, bottom, PANEL);
        context.fill(left, top, right, top + 3, ACCENT);
        context.fill(left, bottom - 2, right, bottom, 0xFF26374A);
        context.fill(left, top + 57, left + 1, bottom - 1, 0xFF26374A);
        context.fill(right - 1, top + 57, right, bottom - 1, 0xFF26374A);
        context.fill(left + 1, top + 56, right - 1, top + 57, 0xFF233246);

        String title = page == KITS_PAGE ? "LOADOUT SELECTOR" : "COMBAT CONTROL CENTER";
        String subtitle = page == KITS_PAGE
                ? "Wähle ein Kit. Vorschau ansehen. Erst nach Bestätigung anwenden."
                : "Dein PvP-Training — schnell erreichbar, sauber gesteuert.";
        int headerWidth = panelWidth() - 48 - (panelWidth() >= 500 ? 112 : 0);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth("HERO BOT  /  " + title, headerWidth)),
                left + 24, top + 17, 0xFFF2F7FF);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(subtitle, panelWidth() - 48)),
                left + 24, top + 37, MUTED);
        if (panelWidth() >= 500) {
            context.fill(right - 124, top + 18, right - 24, top + 40, 0xFF172636);
            context.fill(right - 120, top + 23, right - 114, top + 29, ACCENT);
            context.fill(right - 121, top + 22, right - 113, top + 30, 0x3058D6C4);
            context.drawTextWithShadow(this.textRenderer, Text.literal("CLIENT UI"), right - 106, top + 24, ACCENT);
        }

        if (page == KITS_PAGE) {
            if (!drawFooterStatus.isBlank()) {
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal(this.textRenderer.trimToWidth(drawFooterStatus, panelWidth() - 48)),
                        left + 24, bottom - 57, MUTED);
            }
        } else {
            if (panelHeight() >= 300) {
                int infoY = bottom - 77;
                context.fill(left + 24, infoY, right - 24, bottom - 22, PANEL_LIGHT);
                context.fill(left + 24, infoY, left + 27, bottom - 22, ACCENT);
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal("SHORTCUTS   /hba   ·   /hba kit gui   ·   /hba status"),
                        left + 38, infoY + 11, 0xFFBFD0E5);
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal("Die Oberfläche führt Befehle über den Server aus und prüft Kit-Aktionen vorab."),
                        left + 38, infoY + 31, MUTED);
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawBackdrop(DrawContext context) {
        context.fill(0, 0, this.width, this.height, 0xFF070A11);
        for (int x = 18; x < this.width; x += 52) {
            context.fill(x, 0, x + 1, this.height, 0x101D2938);
        }
        for (int y = 16; y < this.height; y += 52) {
            context.fill(0, y, this.width, y + 1, 0x101D2938);
        }
        int glow = Math.min(180, this.width / 3);
        context.fill(0, 0, glow, 3, 0xFF173A43);
        context.fill(this.width - glow, this.height - 3, this.width, this.height, 0xFF2B1B42);
        for (int i = 0; i < 34; i++) {
            int x = Math.floorMod(i * 137 + 29, Math.max(1, this.width));
            int y = Math.floorMod(i * 83 + 17, Math.max(1, this.height));
            int size = i % 7 == 0 ? 2 : 1;
            int color = i % 3 == 0 ? 0x4058D6C4 : 0x302D8FA0;
            context.fill(x, y, Math.min(this.width, x + size), Math.min(this.height, y + size), color);
        }
    }

    private int panelWidth() {
        return Math.min(this.width, Math.min(800, Math.max(300, this.width - 28)));
    }

    private int panelHeight() {
        return Math.min(this.height, Math.min(470, Math.max(190, this.height - 24)));
    }

    private int panelLeft() {
        return (this.width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (this.height - panelHeight()) / 2;
    }

    private void runCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        this.close();
        if (client.player != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private record KitCardData(String id, String title, String subtitle, int color, ItemStack icon) {
    }

    private final class SidePanel extends ClickableWidget {
        private final KitCardData kit;

        private SidePanel(int x, int y, int width, int height, KitCardData kit) {
            super(x, y, width, height, Text.literal(kit.title()));
            this.kit = kit;
            this.active = false;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            int x = this.getX();
            int y = this.getY();
            context.fill(x, y, x + this.width, y + this.height, PANEL_LIGHT);
            context.fill(x, y, x + this.width, y + 2, this.kit.color());
            context.fill(x + this.width - 43, y + 21, x + this.width - 9, y + 55, 0xFF0B121C);
            context.fill(x + this.width - 43, y + 21, x + this.width - 9, y + 22, this.kit.color());
            context.drawItem(this.kit.icon(), x + this.width - 34, y + 30);
            context.fill(x + 12, y + 75, x + this.width - 12, y + 76, 0xFF2B3B50);
            context.drawTextWithShadow(textRenderer, Text.literal("SELECTED KIT"), x + 12, y + 13, MUTED);
            context.drawTextWithShadow(textRenderer, Text.literal(this.kit.title().toUpperCase()),
                    x + 12, y + 31, this.kit.color());
            context.drawTextWithShadow(textRenderer, Text.literal(this.kit.subtitle()),
                    x + 12, y + 53, 0xFFE0EAF6);
            context.drawTextWithShadow(textRenderer, Text.literal("BEST FOR"), x + 12, y + 87, MUTED);
            context.drawTextWithShadow(textRenderer, Text.literal(kitHint(this.kit.id())),
                    x + 12, y + 105, 0xFFBFD0E5);
            context.drawTextWithShadow(textRenderer, Text.literal("Empfänger"),
                    x + 12, y + this.height - 73, MUTED);
            String recipient = recipientField == null ? rememberedRecipient : recipientField.getText();
            context.drawTextWithShadow(textRenderer,
                    Text.literal(recipient.isBlank() ? "leer = dein eigenes Inventar" : "wird ersetzt"),
                    x + 12, y + this.height - 27, recipient.isBlank() ? MUTED : 0xFFFFC36A);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            this.appendDefaultNarrations(builder);
        }
    }

    private static String kitHint(String id) {
        return switch (id) {
            case "iron", "diamond", "netherite" -> "Schwert-Duelle";
            case "axe", "fc_axe" -> "Axt- und Schildkampf";
            case "mace" -> "Wind-Charge-Mace-Angriffe";
            case "crystal" -> "Crystal PvP und Totem-Spiel";
            case "fc_uhc" -> "UHC-Nahkampf";
            case "fc_misch" -> "Gemischter Schwert-Axt-Kampf";
            default -> "PvP-Training";
        };
    }

    private static final class ActionCard extends ClickableWidget {
        private final String title;
        private final String subtitle;
        private final int accent;
        private final Runnable action;
        private final boolean selected;
        private final ItemStack icon;

        private ActionCard(int x, int y, int width, int height, String title, String subtitle,
                           int accent, Runnable action, boolean selected, ItemStack icon) {
            super(x, y, width, height, Text.literal(title));
            this.title = title;
            this.subtitle = subtitle;
            this.accent = accent;
            this.action = action;
            this.selected = selected;
            this.icon = icon;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            int x = this.getX();
            int y = this.getY();
            var renderer = MinecraftClient.getInstance().textRenderer;
            int color = this.isHovered() ? 0xFF1D3042 : this.selected ? 0xFF172637 : 0xFF121B29;
            context.fill(x, y, x + this.width, y + this.height, color);
            context.fill(x + 3, y, x + this.width, y + 1,
                    this.isHovered() || this.selected ? this.accent : 0xFF1B2938);
            context.fill(x + 3, y + this.height - 1, x + this.width, y + this.height,
                    this.isHovered() || this.selected ? this.accent : 0xFF0B111A);
            context.fill(x, y, x + 3, y + this.height, this.accent);
            boolean showIcon = !this.icon.isEmpty() && this.height >= 28 && this.width >= 108;
            if (showIcon) {
                context.drawItem(this.icon, x + 9, y + (this.height - 16) / 2);
            }
            int textX = showIcon ? x + 32 : x + 11;
            int textWidth = Math.max(1, x + this.width - 10 - textX);
            int titleY = this.subtitle.isBlank() ? y + Math.max(6, (this.height - 9) / 2) : y + 7;
            context.drawTextWithShadow(renderer,
                    Text.literal(renderer.trimToWidth(this.title, textWidth)),
                    textX, titleY, 0xFFF1F6FD);
            if (!this.subtitle.isBlank() && this.height > 32) {
                context.drawTextWithShadow(renderer,
                        Text.literal(renderer.trimToWidth(this.subtitle, textWidth)),
                        textX, y + 27, MUTED);
            } else if (!this.subtitle.isBlank() && this.height >= 28) {
                context.drawTextWithShadow(renderer,
                        Text.literal(renderer.trimToWidth(this.subtitle, textWidth)),
                        textX, y + 18, MUTED);
            }
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            if (this.active && click.button() == 0) this.action.run();
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            this.appendDefaultNarrations(builder);
        }
    }

    private static final class ConfirmKitScreen extends Screen {
        private final HeroBotMenuScreen parent;
        private final KitCardData kit;
        private final String recipient;
        private final boolean startBot;
        private String validationMessage = "";

        private ConfirmKitScreen(HeroBotMenuScreen parent, KitCardData kit,
                                 String recipient, boolean startBot) {
            super(Text.literal("Kit bestätigen"));
            this.parent = parent;
            this.kit = kit;
            this.recipient = recipient;
            this.startBot = startBot;
        }

        @Override
        protected void init() {
            int boxWidth = Math.min(520, this.width - 32);
            int boxLeft = (this.width - boxWidth) / 2;
            int boxTop = (this.height - 218) / 2;
            int buttonGap = 12;
            int buttonWidth = (boxWidth - 48 - buttonGap) / 2;
            int buttonY = boxTop + 166;
            this.addDrawableChild(new ActionCard(boxLeft + 24, buttonY, buttonWidth, 30,
                    "ZURÜCK", "", 0xFF8293A8, () -> MinecraftClient.getInstance().setScreen(parent),
                    false, ItemStack.EMPTY));
            this.addDrawableChild(new ActionCard(boxLeft + 24 + buttonWidth + buttonGap, buttonY,
                    buttonWidth, 30, "BESTÄTIGEN", "", kit.color(), this::confirm,
                    false, ItemStack.EMPTY));
        }

        private void confirm() {
            if (!startBot && !recipient.isBlank() && !recipient.matches("[A-Za-z0-9_]{3,16}")) {
                validationMessage = "Spielername: 3–16 Buchstaben, Zahlen oder Unterstriche.";
                return;
            }
            String command = startBot
                    ? "hba spawn kit " + kit.id() + " PvPTrainer"
                    : "hba kit " + kit.id() + (recipient.isBlank() ? "" : " " + recipient);
            MinecraftClient client = MinecraftClient.getInstance();
            client.setScreen(null);
            if (client.player != null) client.player.networkHandler.sendChatCommand(command);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0xD0000000);
            int boxWidth = Math.min(520, this.width - 32);
            int boxHeight = 218;
            int left = (this.width - boxWidth) / 2;
            int top = (this.height - boxHeight) / 2;
            context.fill(left, top, left + boxWidth, top + boxHeight, 0xFF101824);
            context.fill(left + 1, top + 1, left + boxWidth - 1, top + boxHeight - 1, 0xFF101824);
            context.fill(left, top + boxHeight - 2, left + boxWidth, top + boxHeight, 0xFF26374A);
            context.fill(left, top, left + boxWidth, top + 3, this.kit.color());
            context.drawItem(this.kit.icon(), left + boxWidth - 53, top + 48);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.startBot ? "TRAININGSBOT STARTEN?" : "KIT ANWENDEN?"),
                    left + 24, top + 23, 0xFFF4F7FC);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Loadout: " + this.kit.title()), left + 24, top + 56, this.kit.color());
            String detail = this.startBot
                    ? "PvPTrainer wird mit diesem Kit gegen dich gespawnt."
                    : this.recipient.isBlank()
                    ? "Dein Inventar, deine Rüstung und deine Offhand werden ersetzt."
                    : "Inventar von " + this.recipient + " wird vollständig ersetzt.";
            context.drawTextWithShadow(this.textRenderer, Text.literal(detail),
                    left + 24, top + 81, 0xFFCED8E6);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Bitte vorher wichtige Gegenstände aus dem Inventar nehmen."),
                    left + 24, top + 101, 0xFFFFC36A);
            if (!validationMessage.isBlank()) {
                context.drawTextWithShadow(this.textRenderer, Text.literal(validationMessage),
                        left + 24, top + 132, 0xFFFF7777);
            }
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            MinecraftClient.getInstance().setScreen(this.parent);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}
