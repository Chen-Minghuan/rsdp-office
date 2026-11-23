package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Interprets the main catalog's category paths, names and tags for a saved layout module. */
public final class OfficeFurnitureRelevance {
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^a-z0-9]+");

    private OfficeFurnitureRelevance() {}

    private enum Family { TABLE, OFFICE_DESK, LOW_TABLE, MEETING, TEA, DINING, GAME, SEAT, SOFA, STORAGE, COUNTER, PLANT, BOARD }

    enum Type {
        TABLE(Family.TABLE, "桌类", "桌类", "桌台", "桌子", "小桌", "方桌", "圆桌", "table"),
        OFFICE_DESK(Family.OFFICE_DESK, "办公桌", "办公桌", "职员桌", "职员台", "职员直线桌", "电脑桌", "写字桌", "书桌", "desk"),
        WORKSTATION(Family.OFFICE_DESK, "工位", "屏风卡位", "屏风工作位", "屏风桌", "办公工位", "工作站", "卡位", "工位", "workstation"),
        EXECUTIVE_DESK(Family.OFFICE_DESK, "主管／高管桌", "大班台", "班台", "高管桌", "主管桌", "主管台", "主管直线桌", "高管直线桌", "经理桌", "老板桌", "executive desk"),
        COFFEE_TABLE(Family.LOW_TABLE, "茶几", "茶几", "咖啡几", "咖啡桌", "coffee table", "cocktail table"),
        SIDE_TABLE(Family.LOW_TABLE, "边几", "沙发边几", "沙发侧几", "边几", "角几", "侧几", "边桌", "圆几", "方几", "side table", "end table"),
        MEETING_TABLE(Family.MEETING, "会议／洽谈桌", "会议桌", "会议台", "洽谈桌", "谈判桌", "conference table", "meeting table"),
        TEA_TABLE(Family.TEA, "茶桌", "茶桌", "茶台", "茶艺桌", "tea table"),
        DINING_TABLE(Family.DINING, "餐桌", "餐桌", "餐台", "dining table"),
        GAME_TABLE(Family.GAME, "休闲娱乐桌", "娱乐桌", "游戏桌", "棋牌桌", "麻将桌", "game table"),
        CHAIR(Family.SEAT, "座椅", "座椅", "椅子", "主椅", "椅凳", "chair", "seating", "stool"),
        OFFICE_CHAIR(Family.SEAT, "办公椅", "办公椅", "办公转椅", "职员椅", "电脑椅", "转椅", "office chair", "task chair"),
        EXECUTIVE_CHAIR(Family.SEAT, "总裁椅", "总裁椅", "高管椅", "主管椅", "经理椅", "老板椅", "班椅", "executive chair"),
        MEETING_CHAIR(Family.SEAT, "会议椅", "会议椅", "洽谈椅", "meeting chair", "conference chair"),
        VISITOR_CHAIR(Family.SEAT, "访客椅", "访客椅", "接待椅", "宾客椅", "visitor chair", "guest chair"),
        LOUNGE_CHAIR(Family.SEAT, "休闲单椅", "休闲椅", "休闲单椅", "扶手椅", "单椅", "lounge chair", "armchair"),
        BAR_CHAIR(Family.SEAT, "吧椅", "吧椅", "吧凳", "高脚椅", "bar stool", "bar chair"),
        DINING_CHAIR(Family.SEAT, "餐椅", "餐椅", "餐凳", "dining chair"),
        SOFA(Family.SOFA, "沙发", "沙发", "sofa", "couch"),
        CABINET(Family.STORAGE, "收纳柜", "收纳柜", "文件柜", "柜子", "储物柜", "茶水柜", "矮柜", "高柜", "边柜", "斗柜", "书柜", "cabinet", "credenza"),
        PEDESTAL(Family.STORAGE, "活动柜／底抽柜", "活动柜", "底抽柜", "移动柜", "三抽柜", "推柜", "pedestal"),
        ENTRY_CABINET(Family.STORAGE, "玄关柜", "玄关柜", "门厅柜", "鞋柜", "entry cabinet"),
        COUNTER(Family.COUNTER, "前台", "前台", "接待台", "迎宾台", "reception desk"),
        BAR_COUNTER(Family.COUNTER, "吧台", "吧台", "水吧台", "bar counter"),
        PLANT(Family.PLANT, "绿植", "绿植", "盆栽", "植栽", "plant"),
        BOARD(Family.BOARD, "白板", "白板", "写字板", "展示板", "whiteboard");

        private final Family family;
        final String label;
        private final String[] aliases;

        Type(Family family, String label, String... aliases) {
            this.family = family;
            this.label = label;
            this.aliases = aliases;
        }

        private boolean generic() { return this == TABLE || this == CHAIR; }
    }

    record Match(int points, String reason) {}

    static Type target(JsonNode module) {
        Type glyph = template(module.path("moduleSnapshot").path("glyphTemplateId").asText(""));
        if (glyph != null) return glyph;
        Type builtIn = template(module.path("furnitureTemplateId").asText(""));
        if (builtIn != null) return builtIn;
        Type label = identify(module.path("label").asText(""));
        return label != null ? label : identify(module.path("moduleSnapshot").path("category").asText(""));
    }

    private static Type template(String id) {
        String base = id.endsWith("-desk-only") ? id.substring(0, id.length() - "-desk-only".length()) : id;
        return switch (base) {
            case "desk-straight", "desk-l", "desk-l-rounded", "desk-u" -> Type.OFFICE_DESK;
            case "desk-screen-l", "desk-screen-straight" -> Type.WORKSTATION;
            case "desk-manager", "desk-manager-double", "desk-manager-l", "desk-executive", "desk-executive-l" -> Type.EXECUTIVE_DESK;
            case "coffee-rect", "coffee-round", "coffee-meeting-side" -> Type.COFFEE_TABLE;
            case "table-conference-rect", "table-conference-oval", "table-meeting-round", "table-meeting-square" -> Type.MEETING_TABLE;
            case "table-tea-rect" -> Type.TEA_TABLE;
            case "table-dining-rect" -> Type.DINING_TABLE;
            case "table-game" -> Type.GAME_TABLE;
            case "chair-office" -> Type.OFFICE_CHAIR;
            case "chair-executive" -> Type.EXECUTIVE_CHAIR;
            case "chair-meeting" -> Type.MEETING_CHAIR;
            case "chair-visitor", "chair-meeting-visitor" -> Type.VISITOR_CHAIR;
            case "chair-lounge", "chair-meeting-lounge" -> Type.LOUNGE_CHAIR;
            case "chair-bar", "chair-meeting-stool" -> Type.BAR_CHAIR;
            case "sofa-double", "sofa-triple", "sofa-meeting-single", "sofa-meeting-double", "chair-meeting-booth" -> Type.SOFA;
            case "counter-straight", "counter-l" -> Type.COUNTER;
            case "counter-bar" -> Type.BAR_COUNTER;
            case "cabinet-low", "cabinet-mobile" -> Type.PEDESTAL;
            case "cabinet-short", "cabinet-high" -> Type.CABINET;
            case "cabinet-entry" -> Type.ENTRY_CABINET;
            case "plant" -> Type.PLANT;
            case "whiteboard" -> Type.BOARD;
            default -> base.startsWith("table-conference-") || base.startsWith("table-meeting-") ? Type.MEETING_TABLE
                : base.startsWith("chair-meeting-") ? Type.MEETING_CHAIR : null;
        };
    }

    public static boolean needsTags(OfficeProductDto product) {
        if (product.getFurnitureTags() != null && !product.getFurnitureTags().isEmpty()) return false;
        Type declared = category(product.getCategoryPath()), named = identify(product.getName());
        Type variant = identify(product.getVariantName());
        return !specific(declared) && !specific(named) && !specific(variant);
    }

    static Match match(Type target, OfficeProductDto product) {
        if (target == null) return null;
        Type declared = category(product.getCategoryPath());
        Type named = identify(product.getName());
        Type variant = identify(product.getVariantName());
        if (conflicts(declared, named) || conflicts(declared, variant) || conflicts(named, variant)) return null;

        // A precise name can refine a broad category such as “桌类”.
        Type actual = specific(declared) ? declared : specific(named) ? named : specific(variant) ? variant
            : declared != null ? declared : named != null ? named : variant;
        if (actual != null) {
            if (!compatible(target, actual)) return null;
            if (target == declared) return new Match(85, "同品类 · 商品分类");
            if (target == named || target == variant) return new Match(80, "同品类 · 商品名称或规格名");
            return new Match(actual.generic() ? 47 : 57, "相近品类 · " + actual.label);
        }

        if (product.getFurnitureTags() == null) return null;
        List<Type> tags = product.getFurnitureTags().stream().map(OfficeFurnitureRelevance::tagType)
            .filter(java.util.Objects::nonNull).toList();
        if (tags.isEmpty() || tags.stream().anyMatch(type -> !compatible(target, type))) return null;
        Type tagged = tags.contains(target) ? target : tags.stream().filter(OfficeFurnitureRelevance::specific).findFirst().orElse(tags.get(0));
        return new Match(32, "六维标签相关 · " + tagged.label);
    }

    private static Type category(List<String> path) {
        if (path == null) return null;
        for (int i = path.size() - 1; i >= 0; i--) {
            Type type = identify(path.get(i));
            if (type != null) return type;
        }
        return null;
    }

    private static boolean specific(Type type) { return type != null && !type.generic(); }

    private static boolean conflicts(Type first, Type second) {
        return first != null && second != null && !compatible(first, second);
    }

    private static boolean compatible(Type first, Type second) {
        if (first.family == second.family) return true;
        if (first == Type.TABLE) return isTable(second.family);
        if (second == Type.TABLE) return isTable(first.family);
        return false;
    }

    private static boolean isTable(Family family) {
        return switch (family) {
            case TABLE, OFFICE_DESK, LOW_TABLE, MEETING, TEA, DINING, GAME -> true;
            default -> false;
        };
    }

    private static Type identify(String text) {
        if (text == null || text.isBlank()) return null;
        String words = " " + WORD_SEPARATOR.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip() + " ";
        Type best = null;
        int longest = 0;
        for (Type type : Type.values()) {
            for (String alias : type.aliases) {
                boolean found = alias.charAt(0) < 128 ? words.contains(" " + alias + " ") : text.contains(alias);
                if (found && alias.length() > longest) { best = type; longest = alias.length(); }
            }
        }
        return best;
    }

    private static Type tagType(String text) {
        if (text == null || text.contains("搭配")) return null;
        Type type = identify(text);
        if (type != null) return type;
        if (text.contains("台面") || text.contains("桌面") || text.contains("桌板")) return Type.TABLE;
        if (text.replace("无靠背", "").contains("靠背")) return Type.CHAIR;
        if (text.contains("柜体")) return Type.CABINET;
        return null;
    }
}
