#!/usr/bin/env python3
"""Apply a source-bound, revision-bound manual contour correction through the existing canvas API.
Default is read-only. Original recognition and unrelated objects remain intact; the server keeps canvas history.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen


def request(base, path, payload=None):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = Request(base + path, data=body, headers={'Content-Type': 'application/json'}, method='GET' if body is None else 'PUT')
    with urlopen(req, timeout=20) as response:
        result = json.load(response)
    if result.get('code') != 200:
        raise ValueError(result.get('message', 'API error'))
    return result['data']


def build_payload(canvas, patch):
    if canvas['revision'] != patch['baseCanvasRevision']:
        raise ValueError('画布版本已变化，停止处理；请重新核对当前草稿')
    objects = copy.deepcopy(canvas['objects'])
    ids = set()
    width, height = patch['sourceSize']
    for correction in patch['corrections']:
        zone_id = correction['zoneId']
        matches = [o for o in objects if o.get('id') == zone_id and o.get('kind') == 'zone']
        if len(matches) != 1 or zone_id in ids:
            raise ValueError('空间 ID 缺失、重复或不唯一')
        ids.add(zone_id)
        zone = matches[0]
        if zone.get('name') != correction['name'] or zone.get('sourceAnalysisId') != patch['analysisId'] or zone.get('sourceAnalysisRevision') != patch['analysisRevision']:
            raise ValueError('空间名称或来源版本不匹配')
        if zone.get('polygon') != correction['expectedPolygon']:
            raise ValueError('空间已调整，停止覆盖：' + zone['name'])
        ring = correction['polygon']
        if len(ring) < 3 or len(ring) > 128 or len({tuple(p) for p in ring}) != len(ring):
            raise ValueError('修正轮廓缺失或重复顶点')
        if any(len(p) != 2 or not all(isinstance(v, (float, int)) and math.isfinite(v) for v in p) or not (0 <= p[0] <= width and 0 <= p[1] <= height) for p in ring):
            raise ValueError('修正轮廓超出来源页面')
        # Curved partitions may be represented by a simple, non-orthogonal polygon.
        for i, a in enumerate(ring):
            b = ring[(i + 1) % len(ring)]
            for j in range(i + 2, len(ring)):
                if i == 0 and j == len(ring) - 1:
                    continue
                c, d = ring[j], ring[(j + 1) % len(ring)]
                cross = lambda p, q, r: (q[0]-p[0])*(r[1]-p[1])-(q[1]-p[1])*(r[0]-p[0])
                on = lambda p, q, r: cross(p,q,r) == 0 and min(p[0],q[0]) <= r[0] <= max(p[0],q[0]) and min(p[1],q[1]) <= r[1] <= max(p[1],q[1])
                if cross(a,b,c)*cross(a,b,d) < 0 and cross(c,d,a)*cross(c,d,b) < 0 or on(a,b,c) or on(a,b,d) or on(c,d,a) or on(c,d,b):
                    raise ValueError('修正轮廓自交')
        if abs(sum(a[0]*ring[(i+1)%len(ring)][1]-ring[(i+1)%len(ring)][0]*a[1] for i,a in enumerate(ring))) < 2:
            raise ValueError('修正轮廓退化')
        unit = zone.get('reviewUnit')
        if unit not in ('source', 'mm') or zone.get('sourceYAxis') == 'up':
            raise ValueError('来源坐标或方向不支持此像素修正包')
        scale = zone.get('mmPerSourceUnit') if unit == 'mm' else 1
        if not isinstance(scale, (int, float)) or not math.isfinite(scale) or scale <= 0:
            raise ValueError('缺少可靠的来源比例')
        polygon = [[x*scale, y*scale] for x,y in ring]
        if not zone.get('sourcePolygon'):
            zone['sourcePolygon'] = [[x/scale,y/scale] for x,y in zone['polygon']]
        xs, ys = zip(*polygon)
        zone.update(polygon=polygon, x=min(xs), y=min(ys), w=max(xs)-min(xs), h=max(ys)-min(ys),
                    source='user_annotation', geometrySource='manual_geometry', evidenceKind='MANUAL', confirmed=False)
        zone.pop('confirmedAt', None)
        if unit == 'mm':
            zone.update(actualWidthMm=zone['w'], actualDepthMm=zone['h'])
        else:
            zone.pop('actualWidthMm', None)
            zone.pop('actualDepthMm', None)
    if not ids:
        raise ValueError('修正包为空')
    for obj in objects:
        if obj.get('kind') == 'furniture' and obj.get('zoneId') in ids:
            obj['layoutConfirmed'] = False
    return dict(projectId=patch['projectId'], revision=canvas['revision']+1, objects=objects,
                files=canvas['files'], mode='overview', designZoneId=None, blank=canvas['blank'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('patch', type=Path)
    parser.add_argument('--base-url', default='http://localhost:8082')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup', type=Path)
    args = parser.parse_args()
    patch = json.loads(args.patch.read_text())
    if patch.get('version') != 1 or patch.get('basis') != 'USER_ANNOTATION_SOURCE_PX':
        raise ValueError('修正包版本或来源不支持')
    path = '/api/v1/office/projects/' + quote(patch['projectId'], safe='') + '/canvas'
    canvas = request(args.base_url, path)
    analysis = request(args.base_url, '/api/v1/floor-plan/' + quote(patch['analysisId'], safe=''))
    bounds = analysis.get('previewBounds') or {}
    if analysis.get('analysisRevision') != patch['analysisRevision'] or [bounds.get('maxX'),bounds.get('maxY')] != patch['sourceSize'] or bounds.get('minX') != 0 or bounds.get('minY') != 0:
        raise ValueError('来源版本或页面大小已变化')
    with urlopen(analysis['previewUrl'], timeout=20) as response:
        digest = hashlib.sha256(response.read()).hexdigest()
    if digest != patch['imageSha256']:
        raise ValueError('来源底图哈希不匹配')
    payload = build_payload(canvas, patch)
    print(json.dumps({'mode':'apply' if args.apply else 'preview', 'fromRevision':canvas['revision'],
                      'toRevision':payload['revision'], 'rooms':[c['name'] for c in patch['corrections']]}, ensure_ascii=False))
    if args.apply:
        if not args.backup:
            raise ValueError('应用时必须用 --backup 指定原稿备份路径')
        with args.backup.open('x') as stream:
            json.dump(canvas, stream, ensure_ascii=False, indent=2)
        # Do not race an active edit observed during validation.
        if request(args.base_url, path) != canvas:
            raise ValueError('核查期间画布已改变，未写入')
        request(args.base_url, path, payload)
        saved = request(args.base_url, path)
        if saved['revision'] != payload['revision'] or saved['objects'] != payload['objects']:
            raise ValueError('保存后的画布与修正结果不一致，请使用备份核对')
        print('已保存并回读核对；原稿备份：' + str(args.backup))


if __name__ == '__main__':
    main()
