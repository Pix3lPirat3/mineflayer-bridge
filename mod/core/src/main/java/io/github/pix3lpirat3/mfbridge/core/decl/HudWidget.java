package io.github.pix3lpirat3.mfbridge.core.decl;

/**
 * One widget of a {@link HudPanel}.
 *
 * @param key full object key, {@code <owner>/<extension>/~hud/<panel>/<widget>}
 * @param data decoded generated record of the widget kind: {@code HudTextData}, {@code HudKvData},
 *     {@code HudBarData}, {@code HudListData}, {@code HudBadgeData}, {@code HudSparklineData} or
 *     {@code HudItemData}. Treat packed arrays inside it as read-only.
 */
public record HudWidget(String key, Object data) {}
