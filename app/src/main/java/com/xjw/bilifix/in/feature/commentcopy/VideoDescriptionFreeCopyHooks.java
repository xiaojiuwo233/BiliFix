package com.xjw.bilifix.in.feature.commentcopy;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Makes a long press on the UGC video description open a selectable text dialog. */
public final class VideoDescriptionFreeCopyHooks {
    private static final String INTRO_PACKAGE =
            "com.bilibili.ship.theseus.ugc.intro.ugcheadline.";
    private static final String INTRO_COMPONENT = INTRO_PACKAGE + "UgcIntroductionComponent";

    private final HookApi module;
    private final ClassLoader classLoader;
    private final AtomicInteger dialogLogCount = new AtomicInteger();
    private final Map<TextView, DownState> downStates = new WeakHashMap<>();

    public VideoDescriptionFreeCopyHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        try {
            installTouchHook();
            module.info("video description free copy hook group ready");
        } catch (Throwable throwable) {
            module.error("video description free copy hook group unavailable", throwable);
        }
    }

    private void installTouchHook() throws Throwable {
        Class<?> componentClass = module.load(classLoader, INTRO_COMPONENT);
        CallbackSymbols callback = findDescriptionCallback(componentClass);
        Class<?> touchOwner = findTouchOwner(callback.callbackClass());
        Method onTouchEvent = module.declaredMethod(touchOwner, "onTouchEvent", MotionEvent.class);
        module.deoptimizeFeatureMethod(onTouchEvent);

        module.info("video description copy symbols resolved structurally: callback="
                + callback.callbackClass().getName()
                + " textField=" + callback.textField().getName()
                + " touchOwner=" + touchOwner.getName());

        module.addHook("video description selectable long press", onTouchEvent, chain -> {
            Object receiver = chain.getThisObject();
            Object eventValue = chain.getArg(0);
            if (!(receiver instanceof TextView) || !(eventValue instanceof MotionEvent)) {
                return chain.proceed();
            }
            TextView view = (TextView) receiver;
            MotionEvent event = (MotionEvent) eventValue;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                synchronized (downStates) {
                    downStates.put(view, new DownState(event.getX(), event.getY()));
                }
                return chain.proceed();
            }
            if (action == MotionEvent.ACTION_MOVE) {
                synchronized (downStates) {
                    DownState state = downStates.get(view);
                    if (state != null && movedBeyondTouchSlop(view, state, event)) {
                        downStates.remove(view);
                    }
                }
                return chain.proceed();
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                synchronized (downStates) {
                    downStates.remove(view);
                }
                return chain.proceed();
            }
            if (action != MotionEvent.ACTION_UP) {
                return chain.proceed();
            }

            DownState downState;
            synchronized (downStates) {
                downState = downStates.remove(view);
            }
            if (downState == null || movedBeyondTouchSlop(view, downState, event)) {
                return chain.proceed();
            }

            Object callbackValue = findTouchedCallback(view, event, callback.callbackClass());
            if (callbackValue == null
                    || event.getEventTime() - event.getDownTime()
                    <= ViewConfiguration.getLongPressTimeout()) {
                return chain.proceed();
            }

            module.ensureFeatureSettings(view.getContext());
            if (!module.isCommentFreeCopyEnabled()) {
                return chain.proceed();
            }

            Object textValue = callback.textField().get(callbackValue);
            if (!(textValue instanceof CharSequence)
                    || ((CharSequence) textValue).length() == 0) {
                module.warn("video description free copy text unavailable: callback="
                        + callbackValue.getClass().getName());
                return chain.proceed();
            }

            CharSequence text = new SpannableStringBuilder((CharSequence) textValue);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showSelectableTextDialog(view.getContext(), text);
            return true;
        });
    }

    private static boolean movedBeyondTouchSlop(
            TextView view, DownState state, MotionEvent event) {
        float dx = event.getX() - state.x;
        float dy = event.getY() - state.y;
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        return dx * dx + dy * dy > (float) slop * slop;
    }

    private CallbackSymbols findDescriptionCallback(Class<?> componentClass) throws Throwable {
        Set<String> candidateNames = new LinkedHashSet<>();
        // Exact names verified from international 6.2.6 and 6.3.0. Structural validation
        // below prevents a moved R8 name from hooking an unrelated class.
        candidateNames.add(INTRO_PACKAGE
                + (module.hostVersion().isModern630OrNewer() ? "x" : "v"));
        for (char name = 'a'; name <= 'z'; name++) {
            candidateNames.add(INTRO_PACKAGE + name);
        }
        for (Class<?> declaredClass : componentClass.getDeclaredClasses()) {
            candidateNames.add(declaredClass.getName());
        }

        List<CallbackSymbols> matches = new ArrayList<>();
        for (String candidateName : candidateNames) {
            Class<?> candidate;
            try {
                candidate = Class.forName(candidateName, false, classLoader);
            } catch (ClassNotFoundException ignored) {
                continue;
            }
            Field componentField = findUniqueInstanceField(candidate, componentClass);
            Field textField = findUniqueInstanceField(candidate, SpannableStringBuilder.class);
            if (componentField == null || textField == null
                    || !hasNoArgVoidCallback(candidate)) {
                continue;
            }
            matches.add(new CallbackSymbols(candidate, textField));
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "video description callback candidates=" + matches);
        }
        return matches.get(0);
    }

    private static Field findUniqueInstanceField(Class<?> owner, Class<?> exactType) {
        Field match = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != exactType) {
                continue;
            }
            if (match != null) {
                return null;
            }
            field.setAccessible(true);
            match = field;
        }
        return match;
    }

    private static boolean hasNoArgVoidCallback(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType() == void.class) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> findTouchOwner(Class<?> callbackClass) throws Throwable {
        Class<?> clickableSpan = callbackClass.getSuperclass();
        if (clickableSpan == null) {
            throw new NoSuchMethodException(callbackClass.getName() + " has no superclass");
        }
        // The callback is a span. Its host is the common description TextView base class,
        // which is stable by behavior even though its package is R8-renamed.
        Class<?> viewClass = Class.forName(
                "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView",
                false, callbackClass.getClassLoader());
        Class<?> current = viewClass;
        while (current != null) {
            try {
                current.getDeclaredMethod("onTouchEvent", MotionEvent.class);
                return current;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(viewClass.getName() + ".onTouchEvent(MotionEvent)");
    }

    private static Object findTouchedCallback(
            TextView view, MotionEvent event, Class<?> callbackClass) {
        CharSequence text = view.getText();
        Layout layout = view.getLayout();
        if (!(text instanceof Spanned) || layout == null) {
            return null;
        }
        int localX = (int) event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
        int localY = (int) event.getY() - view.getTotalPaddingTop() + view.getScrollY();
        if (localY < 0 || localY > layout.getHeight()) {
            return null;
        }
        int line = layout.getLineForVertical(localY);
        int offset = layout.getOffsetForHorizontal(line, localX);
        Object[] spans = ((Spanned) text).getSpans(offset, offset, callbackClass);
        return spans.length == 1 ? spans[0] : null;
    }

    private void showSelectableTextDialog(Context context, CharSequence text) {
        try {
            int theme = context.getResources().getIdentifier(
                    "AppTheme.Dialog.Alert", "style", context.getPackageName());
            AlertDialog.Builder builder = theme == 0
                    ? new AlertDialog.Builder(context)
                    : new AlertDialog.Builder(context, theme);
            AlertDialog dialog = builder
                    .setTitle("自由复制")
                    .setMessage(text)
                    .setPositiveButton("完成", null)
                    .setNeutralButton("复制全部", (ignored, which) -> {
                        ClipboardManager manager = (ClipboardManager) context.getSystemService(
                                Context.CLIPBOARD_SERVICE);
                        if (manager != null) {
                            manager.setPrimaryClip(
                                    ClipData.newPlainText("视频简介", text));
                        }
                    })
                    .show();
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextIsSelectable(true);
            }
            int sequence = dialogLogCount.incrementAndGet();
            if (sequence <= 20 || sequence % 100 == 0) {
                module.info("video description free copy dialog shown: chars="
                        + text.length() + " sample=" + sequence);
            }
        } catch (Throwable throwable) {
            module.error("video description free copy dialog failed", throwable);
        }
    }

    private record CallbackSymbols(Class<?> callbackClass, Field textField) {
    }

    private static final class DownState {
        private final float x;
        private final float y;

        private DownState(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
