import type { StyleSpecification } from 'maplibre-gl';

/**
 * First-party 4Riders map style.
 * All geographic vector data is read from the versioned Klang Valley PMTiles
 * archive. No third-party basemap style or tile endpoint is used.
 *
 * Glyphs are font assets only; they do not provide map geometry or tiles.
 */
export function buildMapStyle(archiveUrl: string): StyleSpecification {
  return {
    version: 8,
    name: '4Riders Klang Valley',
    glyphs: 'https://fonts.openmaptiles.org/{fontstack}/{range}.pbf',
    sources: {
      openmaptiles: {
        type: 'vector',
        url: `pmtiles://${archiveUrl}`,
        attribution: '© OpenStreetMap contributors'
      }
    },
    layers: [
      { id: 'background', type: 'background', paint: { 'background-color': '#101014' } },
      {
        id: 'landcover-wood', type: 'fill', source: 'openmaptiles', 'source-layer': 'landcover',
        filter: ['==', ['get', 'class'], 'wood'],
        paint: { 'fill-color': '#20242a', 'fill-opacity': 0.72 }
      },
      {
        id: 'landuse', type: 'fill', source: 'openmaptiles', 'source-layer': 'landuse',
        paint: { 'fill-color': '#191b22', 'fill-opacity': 0.55 }
      },
      {
        id: 'water', type: 'fill', source: 'openmaptiles', 'source-layer': 'water',
        paint: { 'fill-color': '#202a35', 'fill-opacity': 0.92 }
      },
      {
        id: 'waterway', type: 'line', source: 'openmaptiles', 'source-layer': 'waterway',
        paint: { 'line-color': '#344858', 'line-width': ['interpolate', ['linear'], ['zoom'], 8, 0.5, 16, 2] }
      },
      {
        id: 'building-fill', type: 'fill', source: 'openmaptiles', 'source-layer': 'building',
        minzoom: 13,
        paint: { 'fill-color': '#766b80', 'fill-opacity': 0.82 }
      },
      {
        id: 'building-outline', type: 'line', source: 'openmaptiles', 'source-layer': 'building',
        minzoom: 15,
        paint: { 'line-color': '#b7a7c6', 'line-width': ['interpolate', ['linear'], ['zoom'], 15, 0.35, 18, 1.1], 'line-opacity': 0.9 }
      },
      {
        id: 'road-casing', type: 'line', source: 'openmaptiles', 'source-layer': 'transportation',
        paint: {
          'line-color': '#090a0e',
          'line-width': [
            'interpolate', ['linear'], ['zoom'],
            8, ['match', ['get', 'class'], 'motorway', 2.2, 'trunk', 2, 'primary', 1.8, 0.9],
            14, ['match', ['get', 'class'], 'motorway', 7.2, 'trunk', 6.4, 'primary', 5.8, 'secondary', 4.8, 'tertiary', 4, 2.6],
            18, ['match', ['get', 'class'], 'motorway', 17, 'trunk', 15, 'primary', 13, 'secondary', 10, 'tertiary', 8, 4.5]
          ],
          'line-opacity': 0.98
        }
      },
      {
        id: 'roads', type: 'line', source: 'openmaptiles', 'source-layer': 'transportation',
        paint: {
          'line-color': [
            'match', ['get', 'class'],
            'motorway', '#d8a4c8', 'trunk', '#c59acb',
            'primary', '#e0bd91', 'secondary', '#d2c49c',
            'tertiary', '#b8b0c5', 'minor', '#a49caf',
            'residential', '#8f899d', 'service', '#777181', '#9690a2'
          ],
          'line-width': [
            'interpolate', ['linear'], ['zoom'],
            8, ['match', ['get', 'class'], 'motorway', 1.7, 'trunk', 1.5, 'primary', 1.3, 0.65],
            14, ['match', ['get', 'class'], 'motorway', 5.8, 'trunk', 5.2, 'primary', 4.7, 'secondary', 3.9, 'tertiary', 3.2, 2],
            18, ['match', ['get', 'class'], 'motorway', 14, 'trunk', 12.5, 'primary', 11, 'secondary', 8.5, 'tertiary', 7, 3.8]
          ],
          'line-opacity': 1
        }
      },
      {
        id: 'lane-markings-3plus-left', type: 'line', source: 'openmaptiles', 'source-layer': 'transportation',
        minzoom: 15,
        filter: ['all', ['has', 'lanes'], ['>=', ['to-number', ['get', 'lanes']], 3]],
        paint: {
          'line-color': 'rgba(255,255,255,0.78)',
          'line-width': ['interpolate', ['linear'], ['zoom'], 15, 0.65, 18, 1.05],
          'line-offset': ['interpolate', ['linear'], ['zoom'], 15, -1.3, 18, -3.2],
          'line-dasharray': [2.2, 2.2],
          'line-opacity': 0.82
        }
      },
      {
        id: 'lane-markings-3plus-right', type: 'line', source: 'openmaptiles', 'source-layer': 'transportation',
        minzoom: 15,
        filter: ['all', ['has', 'lanes'], ['>=', ['to-number', ['get', 'lanes']], 3]],
        paint: {
          'line-color': 'rgba(255,255,255,0.78)',
          'line-width': ['interpolate', ['linear'], ['zoom'], 15, 0.65, 18, 1.05],
          'line-offset': ['interpolate', ['linear'], ['zoom'], 15, 1.3, 18, 3.2],
          'line-dasharray': [2.2, 2.2],
          'line-opacity': 0.82
        }
      },
      {
        id: 'lane-markings-4plus-middle', type: 'line', source: 'openmaptiles', 'source-layer': 'transportation',
        minzoom: 15,
        filter: ['all', ['has', 'lanes'], ['>=', ['to-number', ['get', 'lanes']], 4]],
        paint: {
          'line-color': 'rgba(255,255,255,0.78)',
          'line-width': ['interpolate', ['linear'], ['zoom'], 15, 0.65, 18, 1.05],
          'line-offset': 0,
          'line-dasharray': [2.2, 2.2],
          'line-opacity': 0.82
        }
      },
      {
        id: 'road-names', type: 'symbol', source: 'openmaptiles', 'source-layer': 'transportation_name',
        minzoom: 13,
        filter: ['has', 'name'],
        layout: {
          'symbol-placement': 'line',
          'text-field': ['get', 'name'],
          'text-font': ['Noto Sans Regular'],
          'text-size': ['interpolate', ['linear'], ['zoom'], 13, 9, 17, 12],
          'text-max-width': 8
        },
        paint: { 'text-color': '#d9d2df', 'text-halo-color': '#17151c', 'text-halo-width': 1.4 }
      },
      {
        id: 'building-names', type: 'symbol', source: 'openmaptiles', 'source-layer': 'building',
        minzoom: 16,
        filter: ['all', ['has', 'name'], ['!=', ['get', 'name'], '']],
        layout: {
          'symbol-placement': 'point',
          'text-field': ['get', 'name'],
          'text-font': ['Noto Sans Regular'],
          'text-size': 10,
          'text-max-width': 10,
          'text-padding': 2
        },
        paint: { 'text-color': '#f5eafc', 'text-halo-color': '#211a2a', 'text-halo-width': 1.5 }
      },
      {
        id: 'place-names', type: 'symbol', source: 'openmaptiles', 'source-layer': 'place',
        minzoom: 5,
        filter: ['has', 'name'],
        layout: {
          'text-field': ['get', 'name'],
          'text-font': ['Noto Sans Regular'],
          'text-size': ['interpolate', ['linear'], ['zoom'], 5, 10, 12, 14, 16, 17],
          'text-max-width': 9
        },
        paint: { 'text-color': '#eee7f3', 'text-halo-color': '#101014', 'text-halo-width': 1.7 }
      }
    ]
  };
}
