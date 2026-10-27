package su.plo.voice.groups.gui.ui;

import su.plo.lib.mod.client.gui.components.Button;
import su.plo.lib.mod.client.gui.components.TextFieldWidget;
import su.plo.lib.mod.client.gui.screen.GuiScreenListener;
import su.plo.lib.mod.client.gui.screen.ScreenWrapper;
import su.plo.lib.mod.client.gui.widget.GuiAbstractWidget;
import su.plo.lib.mod.client.gui.widget.GuiWidgetListener;
import su.plo.lib.mod.client.render.RenderUtil;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.voice.groups.gui.client.GroupsGuiController;
import su.plo.voice.groups.gui.client.GroupsGuiClientAddon;
import su.plo.voice.groups.gui.protocol.*;
import kotlin.Unit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Real Plasmo screens and Fabric input; protocol replies are deterministic fixtures, not a Paper server. */
public class UiScenarios {
    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private Context context;
    private GroupsGuiController controller;
    private GroupsGuiScreen screen;
    private volatile GroupSnapshot current;
    private volatile List<GroupSnapshot> groups;
    private final List<GuiMessage.C2S> requests = new ArrayList<>();
    private int passed;

    public void run(Context context) {
        this.context = context;
        scenario("browse-create", this::browseCreate);
        scenario("password-join", this::passwordJoin);
        scenario("member-permissions", this::memberPermissions);
        scenario("live-membership", this::liveMembership);
        scenario("focus-text-resize", this::focusTextResize);
        scenario("settings-scroll-reset", this::settingsScrollReset);
        scenario("invite-transfer", this::inviteTransfer);
        scenario("kick-ban-unban", this::moderation);
        scenario("confirm-delete-leave", this::confirmation);
        scenario("many-groups-pagination", this::manyGroups);
        System.out.println("PV GUI: PASS " + passed + " shared UI scenarios");
    }

    private void scenario(String name, Runnable test) {
        try {
            test.run();
            context.takeScreenshot("groups-gui-" + name);
            passed++;
            System.out.println("PV GUI: PASS " + name);
        } catch (Throwable failure) {
            context.takeScreenshot("groups-gui-FAILED-" + name);
            throw new AssertionError("UI scenario failed: " + name, failure);
        }
    }

    private void open(GroupSnapshot initial) {
        current = initial;
        groups = of(snapshot(true, 1));
        context.runOnClient(client -> {
            requests.clear();
            controller = new GroupsGuiController(message -> {
                requests.add(message);
                if (message instanceof GuiMessage.C2S.Info) {
                    controller.handle(new GuiMessage.S2C.State(current));
                } else if (message instanceof GuiMessage.C2S.Browse) {
                    GuiMessage.C2S.Browse browse = (GuiMessage.C2S.Browse) message;
                    controller.handle(new GuiMessage.S2C.Browse(new BrowsePage(browse.getPage(), 2, groups,
                            current == null ? null : GROUP)));
                    controller.handle(new GuiMessage.S2C.State(current));
                }
                return Unit.INSTANCE;
            }, runnable -> { runnable.run(); return Unit.INSTANCE; });
            controller.handle(new GuiMessage.S2C.Hello(ProtocolConstants.PROTOCOL_VERSION, true, true, true, true, true));
            controller.handle(new GuiMessage.S2C.Browse(new BrowsePage(1, 2, groups, initial == null ? null : GROUP)));
            controller.handle(new GuiMessage.S2C.State(initial));
            screen = new GroupsGuiScreen(controller);
            context.openScreen(screen);
        });
        context.waitTicks(3);
    }

    private void browseCreate() {
        open(null);
        check(!read(() -> button(tr(GuiLang.BUTTON_JOIN)).isActive()), "join needs selection");
        click(tr(GuiLang.BUTTON_CREATE));
        type("name", "QA Привет");
        type("password", "secret");
        click(tr(GuiLang.BUTTON_CREATE));
        GuiMessage.C2S.Create request = last(GuiMessage.C2S.Create.class);
        check(request.getName().equals("QA Привет") && request.getPassword().equals("secret"), "create payload");
        reply(snapshot(true, 1), GuiLang.SUCCESS_CREATE);
        check(read(() -> view().equals("GROUP")), "create opens group");
        check(read(() -> button("→").isActive()), "owner can transfer");
        check(read(() -> !RenderUtil.getFormattedString(screen.getTitle()).contains("%s")), "no raw title placeholder");
        context.assertSymbols();
        check(!tr(GuiLang.BUTTON_TRANSFER).contains(GuiLang.PREFIX), "transfer translation exists");
    }

    private void passwordJoin() {
        open(null);
        clickContains("QA group");
        click(tr(GuiLang.BUTTON_JOIN));
        check(last(GuiMessage.C2S.Join.class).getPassword() == null, "empty password remains absent");
        context.runOnClient(client -> controller.handle(new GuiMessage.S2C.ActionResult(false, GuiLang.ERROR_WRONG_PASSWORD, of())));
        context.waitTicks(2);
        check(read(() -> view().equals("MAIN")), "wrong password stays on browse");
        type("joinPassword", "secret");
        click(tr(GuiLang.BUTTON_JOIN));
        check(last(GuiMessage.C2S.Join.class).getPassword().equals("secret"), "join sends typed password");
        reply(snapshot(false, 2), GuiLang.SUCCESS_JOIN);
        check(read(() -> view().equals("GROUP")), "successful join opens group");
    }

    private void memberPermissions() {
        open(snapshot(false, 2));
        check(!read(() -> button("→").isActive()), "member cannot transfer");
        check(!read(() -> button("✎").isActive()), "member cannot edit");
        click("✎");
        check(read(() -> view().equals("GROUP")), "disabled settings cannot open");
        check(read(() -> allWidgets().stream().filter(w -> w instanceof Button).count()) == 6,
                "member has no moderation controls for other players");
    }

    private void liveMembership() {
        open(snapshot(true, 1));
        int before = read(() -> list().widgets().size());
        current = snapshot(true, 2);
        context.waitTicks(45);
        check(read(() -> list().widgets().size()) == before + 1, "poll adds newly joined member without reopening");
        check(read(() -> controller.getState().getCurrent().getOnlinePlayers()) == 2, "online count updates");
        current = null;
        context.waitTicks(45);
        check(read(() -> view().equals("MAIN")), "external kick/delete leaves group screen");
    }

    private void focusTextResize() {
        open(null);
        click(tr(GuiLang.BUTTON_CREATE));
        type("name", "QA input");
        context.waitTicks(45);
        context.getInput().typeChars(" remains");
        check(read(() -> field("name").getValue()).equals("QA input remains"), "poll preserves text and keyboard focus");
        context.getInput().resizeWindow(1280, 800);
        context.waitTicks(3);
        context.getInput().typeChars(" resized");
        check(read(() -> field("name").getValue()).equals("QA input remains resized"), "resize preserves text and focus");
        context.getInput().resizeWindow(640, 480);
        context.waitTicks(3);
        bounds();
        context.getInput().resizeWindow(960, 640);
        context.waitTicks(3);
        bounds();
    }

    private void settingsScrollReset() {
        open(snapshot(true, 2));
        click("✎");
        type("password", "unsaved password");
        scrollToBottom();
        double offset = read(() -> list().getOffset());
        check(offset > 0, "settings actually scroll");
        click(tr(GuiLang.BUTTON_PERSISTENT_OFF));
        check(read(() -> list().getOffset()) == offset, "toggle preserves scroll after native first render");
        click(tr(GuiLang.BUTTON_REMOVE_PASSWORD));
        check(read(() -> field("password").getValue()).isEmpty(), "remove password clears unsaved draft");
        check(last(GuiMessage.C2S.SetPassword.class).getPassword() == null, "remove password sends null");
        reply(snapshot(true, 2, false, of("qa.join")), GuiLang.SUCCESS_PASSWORD_UNSET);
        scrollToBottom();
        click(tr(GuiLang.BUTTON_CLEAR_PERMISSIONS));
        check(read(() -> field("permissions").getValue()).isEmpty(), "clear permissions clears draft");
        check(last(GuiMessage.C2S.SetPermissions.class).getPermissions() == null, "clear permissions sends null");
        reply(snapshot(true, 2, false, of()), GuiLang.SUCCESS_PERMISSIONS_UNSET);
        scrollToBottom();
        double before = read(() -> list().getOffset());
        context.runOnClient(client -> controller.handle(new GuiMessage.S2C.State(snapshot(true, 3, false, of()))));
        context.waitTicks(3);
        check(read(() -> list().getOffset()) == before, "membership update preserves settings scroll");
        click(tr(GuiLang.BUTTON_SAVE));
        check(read(() -> requests.stream().noneMatch(m -> m instanceof GuiMessage.C2S.SetPassword && ((GuiMessage.C2S.SetPassword) m).getPassword() != null)), "save cannot restore removed password");
        check(read(() -> requests.stream().noneMatch(m -> m instanceof GuiMessage.C2S.SetPermissions && ((GuiMessage.C2S.SetPermissions) m).getPermissions() != null)), "save cannot restore removed permissions");
    }

    private void inviteTransfer() {
        open(snapshot(true, 2));
        context.runOnClient(client -> controller.handle(new GuiMessage.S2C.InviteCandidates(of(new PlayerRef(OTHER, "Guest")))));
        click("+");
        type("filter", "Gue");
        check(read(() -> field("filter").getValue()).equals("Gue"), "filter accepts all characters");
        clickContains("Guest");
        click(tr(GuiLang.BUTTON_CONFIRM_INVITE));
        check(last(GuiMessage.C2S.Invite.class).getPlayerName().equals("Guest"), "invite targets selection");
        reply(snapshot(true, 2), GuiLang.SUCCESS_INVITE);
        click("+");
        type("filter", "Gue");
        current = snapshot(true, 3);
        context.waitTicks(45);
        check(read(() -> view().equals("INVITE")), "old invite success cannot close a newly opened form on refresh");
        check(read(() -> field("filter").getValue()).equals("Gue"), "invite filter survives membership update");
        click(tr(GuiLang.BUTTON_BACK));
        click("→");
        clickContains("Member1");
        click(tr(GuiLang.BUTTON_CONFIRM_TRANSFER));
        check(read(() -> view().equals("CONFIRM")), "transfer asks confirmation");
        click(tr(GuiLang.BUTTON_CONFIRM_YES));
        check(last(GuiMessage.C2S.Transfer.class).getPlayerName().equals("Member1"), "transfer targets selection");
        reply(snapshot(false, 2), GuiLang.SUCCESS_TRANSFER);
        check(!read(() -> button("→").isActive()), "ownership change revokes controls");
    }

    private void moderation() {
        open(snapshot(true, 2));
        click("-");
        click(tr(GuiLang.BUTTON_CONFIRM_NO));
        check(read(() -> requests.stream().noneMatch(m -> m instanceof GuiMessage.C2S.Kick)), "cancel does not kick");
        click("-");
        click(tr(GuiLang.BUTTON_CONFIRM_YES));
        check(last(GuiMessage.C2S.Kick.class).getPlayerName().equals("Member1"), "kick targets member");
        reply(snapshot(true, 2), GuiLang.SUCCESS_KICK);
        // The toolbar has a second ×; select the inline member button by its Y position.
        clickWidget(read(() -> allWidgets().stream().filter(w -> w instanceof Button && text(w).equals("×"))
                .filter(w -> w.getY() < panel().getToolbarY() && w.getY() > panel().getY() + 35).findFirst().orElseThrow(() -> new AssertionError("Missing test widget"))));
        click(tr(GuiLang.BUTTON_CONFIRM_YES));
        check(last(GuiMessage.C2S.Ban.class).getPlayerName().equals("Member1"), "ban targets member");
        GroupSnapshot banned = snapshot(true, 1);
        banned = banned.copy(banned.getId(), banned.getName(), banned.getOwnerName(), 1, 1, true, false,
                of(), banned.getMembers(), of(new PlayerRef(new UUID(0, 11), "Member1")), true, true, true, true,
                true, true, true, true, true, true, true);
        reply(banned, GuiLang.SUCCESS_BAN);
        clickWidget(read(() -> allWidgets().stream().filter(w -> w instanceof Button && text(w).equals("+"))
                .filter(w -> w.getY() < panel().getToolbarY()).findFirst().orElseThrow(() -> new AssertionError("Missing test widget"))));
        check(last(GuiMessage.C2S.Unban.class).getPlayerName().equals("Member1"), "unban targets banned player");
        reply(snapshot(true, 1), GuiLang.SUCCESS_UNBAN);
    }

    private void confirmation() {
        open(snapshot(true, 1));
        clickWidget(read(() -> buttonAtToolbar("×")));
        click(tr(GuiLang.BUTTON_CONFIRM_NO));
        check(read(() -> requests.stream().noneMatch(m -> m instanceof GuiMessage.C2S.Delete)), "cancel does not delete");
        clickWidget(read(() -> buttonAtToolbar("×")));
        click(tr(GuiLang.BUTTON_CONFIRM_YES));
        last(GuiMessage.C2S.Delete.class);
        groups = of();
        reply(null, GuiLang.SUCCESS_DELETE);
        check(read(() -> view().equals("MAIN")), "deleted group returns to browse");
        open(snapshot(false, 2));
        clickWidget(read(() -> buttonAtToolbar("×")));
        click(tr(GuiLang.BUTTON_CONFIRM_YES));
        last(GuiMessage.C2S.Leave.class);
        reply(null, GuiLang.SUCCESS_LEAVE);
        check(read(() -> view().equals("MAIN")), "leave returns to browse");
    }

    private void manyGroups() {
        open(null);
        groups = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> snapshot(new UUID(1, i), "QA group " + i, true, 2, true, of())).collect(java.util.stream.Collectors.toList());
        context.runOnClient(client -> controller.handle(new GuiMessage.S2C.Browse(new BrowsePage(1, 2, groups, null))));
        context.waitTicks(3);
        scrollToBottom();
        check(read(() -> list().getOffset()) > 0, "many groups scroll");
        click(tr(GuiLang.BUTTON_NEXT));
        check(last(GuiMessage.C2S.Browse.class).getPage() == 2, "next page request");
        check(!read(() -> button(tr(GuiLang.BUTTON_NEXT)).isActive()), "last page disables next");
        click(tr(GuiLang.BUTTON_PREV));
        check(last(GuiMessage.C2S.Browse.class).getPage() == 1, "previous page request");
        bounds();
    }

    private GroupSnapshot snapshot(boolean owner, int members) {
        return snapshot(owner, members, true, of("qa.join"));
    }

    private GroupSnapshot snapshot(boolean owner, int members, boolean password, List<String> permissions) {
        return snapshot(GROUP, "QA group — длинное название", owner, members, password, permissions);
    }

    private GroupSnapshot snapshot(UUID id, String name, boolean owner, int members, boolean password, List<String> permissions) {
        ArrayList<GroupMember> people = new ArrayList<GroupMember>();
        people.add(new GroupMember(OWNER, "Owner", true));
        for (int i = 1; i < members; i++) people.add(new GroupMember(new UUID(0, 10 + i), "Member" + i, false));
        return new GroupSnapshot(id, name, "Owner", members, members,
                password, false, permissions, people, of(), false, owner, owner, owner,
                owner, owner, owner, owner, owner, owner, owner);
    }

    private void reply(GroupSnapshot next, String success) {
        current = next;
        context.runOnClient(client -> {
            controller.handle(new GuiMessage.S2C.ActionResult(true, success, of()));
            controller.handle(new GuiMessage.S2C.State(next));
        });
        context.waitTicks(3);
    }

    private void click(String label) { clickWidget(read(() -> button(label))); }
    private void clickContains(String label) {
        clickWidget(read(() -> allWidgets().stream().filter(w -> w instanceof Button && text(w).contains(label))
                .findFirst().orElseThrow(() -> new AssertionError("Missing button containing " + label))));
    }
    private void clickWidget(GuiAbstractWidget widget) {
        double[] position = read(() -> new double[] {
                (widget.getX() + widget.getWidth() / 2.0),
                (widget.getY() + widget.getHeight() / 2.0)});
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().pressMouse(0);
        context.waitTicks(3);
    }
    private void type(String id, String value) {
        clickWidget(read(() -> field(id)));
        context.getInput().typeChars(value);
        context.waitTick();
    }
    private void scrollToBottom() {
        double[] position = read(() -> new double[] {
                (screen.getWidth() / 2.0),
                ((int) reflect(screen, "bodyTop") + 10.0)});
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().scroll(-30);
        context.waitTicks(3);
    }
    private void bounds() {
        context.runOnClient(client -> {
            GroupsGuiPanelLayout p = panel();
            check(p.getX() >= 0 && p.getY() >= 0 && p.getRight() <= screen.getWidth() && p.getBottom() <= screen.getHeight(), "panel fits viewport");
            screen.widgets().stream().filter(w -> w instanceof Button).map(w -> (Button) w).forEach(w ->
                    check(w.getX() >= p.getX() && w.getX() + w.getWidth() <= p.getRight()
                            && w.getY() >= p.getY() && w.getY() + w.getHeight() <= p.getBottom(), "root button fits panel"));
        });
    }
    private Button button(String label) {
        return (Button) allWidgets().stream().filter(w -> w instanceof Button && text(w).equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing button " + label + "; found " + allWidgets().stream().map(this::text).collect(java.util.stream.Collectors.toList())));
    }
    private Button buttonAtToolbar(String label) {
        return (Button) allWidgets().stream().filter(w -> w instanceof Button && text(w).equals(label) && w.getY() == panel().getToolbarY()).findFirst().orElseThrow(() -> new AssertionError("Missing test widget"));
    }
    private List<GuiAbstractWidget> allWidgets() {
        ArrayList<GuiAbstractWidget> result = new ArrayList<GuiAbstractWidget>();
        collect(screen, result);
        return result;
    }
    private void collect(GuiWidgetListener listener, List<GuiAbstractWidget> result) {
        if (listener instanceof GuiAbstractWidget) result.add((GuiAbstractWidget) listener);
        if (listener instanceof GuiScreenListener) ((GuiScreenListener) listener).widgets().forEach(child -> collect(child, result));
    }
    @SuppressWarnings("unchecked")
    private TextFieldWidget field(String id) { return ((Map<String, TextFieldWidget>) reflect(screen, "fields")).get(id); }
    private GroupsGuiList list() { return (GroupsGuiList) reflect(screen, "list"); }
    private GroupsGuiPanelLayout panel() { return (GroupsGuiPanelLayout) reflect(screen, "panel"); }
    private String view() { return reflect(screen, "view").toString(); }
    private String text(GuiAbstractWidget widget) { return RenderUtil.getFormattedString(widget.getText()).replaceAll("§.", ""); }
    private String tr(String key) { return RenderUtil.getFormattedString(McTextComponent.translatable(key)).replaceAll("§.", ""); }
    private <T> T read(Supplier<T> value) { return context.computeOnClient(client -> value.get()); }
    private <T extends GuiMessage.C2S> T last(Class<T> type) {
        return read(() -> java.util.stream.IntStream.range(0, requests.size()).mapToObj(i -> requests.get(requests.size() - 1 - i)).filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new AssertionError("No request " + type.getSimpleName())));
    }
    private static Object reflect(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @SafeVarargs
    private static <T> List<T> of(T... values) { return java.util.Arrays.asList(values); }

    public interface Context {
        void runOnClient(java.util.function.Consumer<Object> action);
        <T> T computeOnClient(java.util.function.Function<Object, T> action);
        void waitTicks(int ticks);
        default void waitTick() { waitTicks(1); }
        void openScreen(GroupsGuiScreen screen);
        void takeScreenshot(String name);
        void assertSymbols();
        Input getInput();
    }

    public interface Input {
        void setCursorPos(double x, double y);
        void pressMouse(int button);
        void typeChars(String text);
        void scroll(double amount);
        void resizeWindow(int width, int height);
    }

}
