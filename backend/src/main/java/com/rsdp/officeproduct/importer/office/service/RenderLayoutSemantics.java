package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.stream.Collectors;

/** Deterministic arrangement facts derived from the same geometry sent to rendering. */
final class RenderLayoutSemantics {
    private RenderLayoutSemantics() {}
    private record Local(RenderSpatialControl.FurnitureControl item,double x,double y) {}

    static ArrayNode manifest(RenderSpatialControl.Prepared spatial) {
        ArrayNode result=JsonNodeFactory.instance.arrayNode();
        List<RenderSpatialControl.FurnitureControl> primaries=spatial.furniture().stream().filter(item->!seat(item)).toList();
        List<RenderSpatialControl.FurnitureControl> seats=spatial.furniture().stream().filter(RenderLayoutSemantics::seat).toList();
        boolean structured=false;
        for(var primary:primaries) {
            List<Local> locals=new ArrayList<>();double angle=Math.toRadians(primary.rotation());
            for(var seat:seats.stream().filter(item->nearest(primaries,item).furnitureId().equals(primary.furnitureId())).toList()) {
                double dx=seat.centerXmm()-primary.centerXmm(),dy=seat.centerYmm()-primary.centerYmm();
                locals.add(new Local(seat,dx*Math.cos(angle)+dy*Math.sin(angle),-dx*Math.sin(angle)+dy*Math.cos(angle)));
            }
            List<Local> heads=locals.stream().filter(local->Math.abs(local.x())>primary.widthMm()*.325&&Math.abs(local.y())<=Math.max(primary.depthMm(),1)*.75).toList();
            List<Local> side=locals.stream().filter(local->!heads.contains(local)).toList();
            List<Local> negative=side.stream().filter(local->local.y()<0).sorted(Comparator.comparingDouble(Local::x)).toList();
            List<Local> positive=side.stream().filter(local->local.y()>=0).sorted(Comparator.comparingDouble(Local::x)).toList();
            if(negative.size()>=2&&positive.size()>=2) {
                structured=true;ObjectNode fact=text("两侧座椅组成两条彼此相对的直线排，不是交错、弧形或散乱布局；两排均与"+primary.referenceId()+"的长边平行。");fact.put("kind","TWO_ROWS").put("primaryF",primary.referenceId());result.add(fact);
            }
            if(negative.size()>=2)rowFacts(result,primary,negative,"一侧");if(positive.size()>=2)rowFacts(result,primary,positive,"另一侧");
            for(Local head:heads) {
                structured=true;String direction=direction(head.item().centerXmm()-primary.centerXmm(),head.item().centerYmm()-primary.centerYmm());
                ObjectNode fact=text(head.item().referenceId()+"位于"+primary.referenceId()+direction+"端部，短边中心线偏差"+Math.round(Math.abs(head.y()))+"mm，旋转"+head.item().rotation()+"°；保持端部居中关系，不得挪到侧排或桌角。");fact.put("kind","HEAD_SEAT").put("primaryF",primary.referenceId()).put("primaryType",primary.type()).put("furnitureRef",head.item().referenceId()).put("productRef",head.item().productReferenceId()).put("direction",direction);result.add(fact);
            }
        }
        if(!structured) for(var seat:seats) {
            var nearest=primaries.stream().min(Comparator.comparingDouble(primary->distance(primary,seat))).orElse(null);if(nearest==null)continue;
            result.add(text(seat.referenceId()+"位于"+nearest.referenceId()+direction(seat.centerXmm()-nearest.centerXmm(),seat.centerYmm()-nearest.centerYmm())+"，保持现有相对位置与"+seat.rotation()+"°朝向。"));
        }
        if(result.isEmpty())result.add(text("严格保持图2所示各家具的相对位置、排列连线和实际间距，不重新组织布局。"));
        return result;
    }
    private static void rowFacts(ArrayNode result,RenderSpatialControl.FurnitureControl primary,List<Local> row,String fallbackSide) {
        double meanX=row.stream().mapToDouble(local->local.item().centerXmm()).average().orElse(0),meanY=row.stream().mapToDouble(local->local.item().centerYmm()).average().orElse(0);
        String side=direction(meanX-primary.centerXmm(),meanY-primary.centerYmm());if(side.equals("中部"))side=fallbackSide;
        double perpendicular=row.stream().mapToDouble(Local::y).max().orElse(0)-row.stream().mapToDouble(Local::y).min().orElse(0);
        String sequence=row.stream().map(local->local.item().referenceId()).collect(Collectors.joining("→"));List<String> gaps=new ArrayList<>();
        for(int i=1;i<row.size();i++)gaps.add(String.valueOf(Math.round(distance(row.get(i-1).item(),row.get(i).item()))));
        String alignment=perpendicular<=1?"中心共线，组成直线一排":"近似直线排列，垂直中心线最大偏差"+Math.round(perpendicular)+"mm";
        String rotations=row.stream().map(local->local.item().referenceId()+"="+local.item().rotation()+"°").collect(Collectors.joining("、"));
        ObjectNode fact=text(primary.referenceId()+side+"："+sequence+"，"+alignment+"，相邻中心间距依次"+String.join("、",gaps)+"mm，朝向"+rotations+"。保持该顺序、中心连线和实际间距，不得交错、弧形、散开或改成随意角度。");fact.put("kind","SIDE_ROW").put("primaryF",primary.referenceId()).put("primaryType",primary.type()).put("side",side).put("count",row.size());var refs=fact.putArray("furnitureRefs");row.forEach(item->refs.add(item.item().referenceId()));var products=fact.putArray("productRefs");row.stream().map(item->item.item().productReferenceId()).filter(value->!value.isBlank()).distinct().forEach(products::add);result.add(fact);
    }
    private static ObjectNode text(String description) { ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("description",description);return result; }
    private static String direction(double dx,double dy) {
        if(Math.abs(dx)<1&&Math.abs(dy)<1)return"中部";if(Math.abs(dx)>Math.abs(dy)*1.5)return dx<0?"左侧":"右侧";if(Math.abs(dy)>Math.abs(dx)*1.5)return dy<0?"后侧":"前侧";
        return(dy<0?"后":"前")+(dx<0?"左":"右")+"侧";
    }
    private static double distance(RenderSpatialControl.FurnitureControl a,RenderSpatialControl.FurnitureControl b) { return Math.hypot(a.centerXmm()-b.centerXmm(),a.centerYmm()-b.centerYmm()); }
    private static boolean seat(RenderSpatialControl.FurnitureControl item) {
        String role=item.source().path("layoutRole").asText("");return "seat".equals(role)||role.isBlank()&&item.type().contains("椅");
    }
    private static RenderSpatialControl.FurnitureControl nearest(List<RenderSpatialControl.FurnitureControl> primaries,RenderSpatialControl.FurnitureControl seat) {
        return primaries.stream().min(Comparator.comparingDouble(primary->distance(primary,seat))).orElseThrow();
    }
}
