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
            8, ['match', ['get', 'class'], 'motorway', 1.4, 'trunk', 1.2, 'primary', 1, 0.45],
            14, ['match', ['get', 'class'], 'motorway', 5, 'trunk', 4.2, 'primary', 3.4, 'secondary', 2.8, 'tertiary', 2.2, 1.1],
            18, ['match', ['get', 'class'], 'motorway', 11, 'trunk', 9, 'primary', 7, 'secondary', 5, 'tertiary', 4, 2]
          ],
          'line-opacity': 0.96
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
