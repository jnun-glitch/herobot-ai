package de.codex.herobotai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class TrainingScreen extends Screen {
    private static final int ROW_HEIGHT = 34;
    private static final int LIST_TOP = 66;
    private final Screen parent;
    private TextFieldWidget notesField;
    private int scrollOffset;
    private String feedback = "Klicke rechts auf einen Status: Unbekannt → Kann → Kann nicht.";

    public TrainingScreen(Screen parent) {
        super(Text.literal("HeroBot AI Training"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        boolean compactButtons = this.width < 470;
        int buttonWidth = compactButtons ? Math.max(110, (this.width - 64) / 2) : 96;
        int buttonGap = compactButtons ? 8 : 6;
        int totalWidth = compactButtons ? 2 * buttonWidth + buttonGap : 4 * buttonWidth + 3 * buttonGap;
        int startX = (this.width - totalWidth) / 2;
        int[] buttonX = compactButtons
                ? new int[]{startX, startX + buttonWidth + buttonGap, startX, startX + buttonWidth + buttonGap}
                : new int[]{startX, startX + buttonWidth + buttonGap,
                startX + 2 * (buttonWidth + buttonGap), startX + 3 * (buttonWidth + buttonGap)};
        int[] buttonY = compactButtons
                ? new int[]{this.height - 54, this.height - 54, this.height - 28, this.height - 28}
                : new int[]{this.height - 34, this.height - 34, this.height - 34, this.height - 34};
        int visibleRows = Math.max(1, (this.height - 114 - LIST_TOP) / ROW_HEIGHT);
        this.scrollOffset = Math.min(this.scrollOffset, Math.max(0, Capability.values().length - visibleRows));

        this.notesField = new TextFieldWidget(this.textRenderer, 28, this.height - 78,
                this.width - 56, 20, Text.literal("Beobachtung"));
        this.notesField.setMaxLength(500);
        this.notesField.setPlaceholder(Text.literal("Was klappt? Was klappt noch nicht?"));
        this.notesField.setText(TrainingStore.notes());
        this.addDrawableChild(this.notesField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Snapshot"), button -> {
            DiagnosticsLog.captureSnapshot();
            this.feedback = "Snapshot im Debug-Log gespeichert.";
        }).dimensions(buttonX[0], buttonY[0], buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Bericht kopieren"), button -> {
            TrainingStore.save(this.notesField.getText());
            MinecraftClient.getInstance().keyboard.setClipboard(TrainingStore.createShareReport());
            this.feedback = "Bericht kopiert. Füge ihn im Chat ein, damit ich ihn auswerten kann.";
        }).dimensions(buttonX[1], buttonY[1], buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Speichern"), button -> {
            TrainingStore.save(this.notesField.getText());
            this.feedback = "Trainingsangaben gespeichert.";
        }).dimensions(buttonX[2], buttonY[2], buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Schließen"), button -> this.close())
                .dimensions(buttonX[3], buttonY[3], buttonWidth, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xF010141B);
        context.fill(0, 0, this.width, 54, 0xFF202B38);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("HeroBot AI Training"),
                this.width / 2, 13, 0xFFFFFFFF);
        boolean herobotLoaded = DiagnosticsLog.isHeroBotLoaded();
        String herobotState = herobotLoaded ? "HeroBot erkannt" : "HeroBot nicht erkannt";
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(herobotState + " · Angaben und Logs bleiben lokal"),
                this.width / 2, 34, herobotLoaded ? 0xFF9DE2A1 : 0xFFFFD18A);

        int listBottom = this.height - 114;
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Fähigkeiten — Status rechts anklicken; Mausrad zum Scrollen"),
                28, 56, 0xFFE0E7EF);
        context.enableScissor(24, LIST_TOP, this.width - 24, listBottom);
        for (int index = scrollOffset; index < Capability.values().length; index++) {
            int rowY = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT > listBottom) {
                break;
            }
            Capability capability = Capability.values()[index];
            CapabilityStatus status = TrainingStore.status(capability);
            int statusLeft = this.width - 172;
            context.fill(28, rowY, this.width - 28, rowY + ROW_HEIGHT - 2,
                    index % 2 == 0 ? 0xAA273340 : 0xAA202B36);
            String fittedLabel = this.textRenderer.trimToWidth(capability.label(), Math.max(16, statusLeft - 52));
            context.drawTextWithShadow(this.textRenderer, Text.literal(fittedLabel),
                    40, rowY + 10, 0xFFFFFFFF);

            int statusRight = this.width - 40;
            int statusColor = switch (status) {
                case UNKNOWN -> 0xFF526273;
                case CAN -> 0xFF287A4A;
                case CANNOT -> 0xFF9A3E3E;
            };
            context.fill(statusLeft, rowY + 4, statusRight, rowY + ROW_HEIGHT - 6, statusColor);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status.label()),
                    (statusLeft + statusRight) / 2, rowY + 9, 0xFFFFFFFF);
        }
        context.disableScissor();

        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Logdatei: config/herobot-ai-training/debug.log"),
                28, this.height - 99, 0xFF9BA8B7);
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.feedback),
                28, this.height - 91, 0xFFB8D9FF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int listBottom = this.height - 114;
        if (click.button() == 0 && mouseY >= LIST_TOP && mouseY < listBottom
                && mouseX >= this.width - 172 && mouseX <= this.width - 40) {
            int visibleIndex = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            int capabilityIndex = scrollOffset + visibleIndex;
            if (capabilityIndex >= 0 && capabilityIndex < Capability.values().length) {
                Capability capability = Capability.values()[capabilityIndex];
                TrainingStore.save(this.notesField == null ? TrainingStore.notes() : this.notesField.getText());
                TrainingStore.setStatus(capability, TrainingStore.status(capability).next());
                this.feedback = capability.label() + ": " + TrainingStore.status(capability).label();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listBottom = this.height - 114;
        if (mouseY >= LIST_TOP && mouseY < listBottom) {
            int visibleRows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
            int maxOffset = Math.max(0, Capability.values().length - visibleRows);
            this.scrollOffset = Math.max(0, Math.min(maxOffset,
                    this.scrollOffset + (verticalAmount < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        if (this.notesField != null) {
            TrainingStore.save(this.notesField.getText());
        }
        MinecraftClient.getInstance().setScreen(this.parent);
    }
}
