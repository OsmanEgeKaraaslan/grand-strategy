import { Canvas, useThree, type ThreeEvent } from '@react-three/fiber'
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent, type RefObject } from 'react'
import * as THREE from 'three'
import type { GameMap, MapProvince } from '../api/types'
import { parsePolygons } from '../lib/mapGeometry'
import type { TargetKind, UnitCounts } from './ProvinceMap'

export interface MapView {
  x: number
  y: number
  w: number
  h: number
}

export interface Map3DProps {
  map: GameMap
  /** Province colour per key, as the active 2D map mode paints it. */
  fills: Map<string, string>
  selectedKey: string | null
  targets: Map<string, TargetKind>
  units: Map<string, UnitCounts>
  ownColor: string
  enemyColor: string
  alliedColor: string
  /** The 2D view (viewBox): the camera looks at its centre from a height that follows its width. */
  view: MapView
  onSelect: (provinceKey: string) => void
  onCommand?: (provinceKey: string) => void
  onSelectArmy?: (provinceKey: string) => void
  onHover: (province: MapProvince | null, clientX: number, clientY: number) => void
  /** Drag pans and the wheel zooms the shared view (fractions of the view / zoom factors). */
  onPan: (dx: number, dy: number) => void
  onZoom: (factor: number) => void
}

const PLAINS_HEIGHT = 6
const MOUNTAIN_HEIGHT = 22
const SELECTED_LIFT = 6

/**
 * Optional 2.5D campaign map (UI-B): provinces extruded from the template outlines (mountains taller),
 * coloured like the 2D map mode, army stacks as coloured columns. Same orders as the 2D map: click
 * selects, right-click orders the selected army, clicking your column selects your army. Loaded
 * lazily (three.js stays out of the main bundle); the 2D map remains the accessible default.
 */
export default function Map3D(props: Map3DProps) {
  const { view, onPan, onZoom } = props
  const wrapper = useRef<HTMLDivElement>(null)
  const drag = useRef<{ x: number; y: number; moved: boolean } | null>(null)
  const dragged = useRef(false)

  // Wheel zoom needs a non-passive listener so the page does not scroll.
  useEffect(() => {
    const element = wrapper.current
    if (!element) return
    function onWheel(event: WheelEvent) {
      event.preventDefault()
      onZoom(event.deltaY < 0 ? 1.2 : 1 / 1.2)
    }
    element.addEventListener('wheel', onWheel, { passive: false })
    return () => element.removeEventListener('wheel', onWheel)
  }, [onZoom])

  function down(event: ReactPointerEvent) {
    drag.current = { x: event.clientX, y: event.clientY, moved: false }
    dragged.current = false
  }

  function move(event: ReactPointerEvent) {
    const d = drag.current
    const rect = wrapper.current?.getBoundingClientRect()
    if (!d || !rect || rect.width === 0) return
    const dx = event.clientX - d.x
    const dy = event.clientY - d.y
    if (!d.moved && Math.hypot(dx, dy) < 5) return
    d.moved = true
    dragged.current = true
    props.onHover(null, 0, 0)
    onPan(-dx / rect.width, -dy / rect.height)
    drag.current = { x: event.clientX, y: event.clientY, moved: true }
  }

  function up() {
    drag.current = null
  }

  return (
    <div
      ref={wrapper}
      className="province-map province-map--3d"
      tabIndex={0}
      role="img"
      aria-label={`3D view of ${props.map.name}. Click a province to select it, click your army column to select the army, right-click to order it. Use the 2D map for keyboard play.`}
      onPointerDown={down}
      onPointerMove={move}
      onPointerUp={up}
      onPointerLeave={() => {
        up()
        props.onHover(null, 0, 0)
      }}
      onContextMenu={(event) => event.preventDefault()}
    >
      <Canvas frameloop="demand" dpr={[1, 2]} camera={{ fov: 45, near: 1, far: 20000 }}>
        <CameraRig view={view} />
        <ambientLight intensity={0.65} />
        <directionalLight position={[view.x, 2500, view.y - 600]} intensity={1.25} />
        <mesh rotation={[-Math.PI / 2, 0, 0]} position={[view.x + view.w / 2, -0.5, view.y + view.h / 2]}>
          <planeGeometry args={[40000, 40000]} />
          <meshStandardMaterial color="#12324a" />
        </mesh>
        <Provinces {...props} dragged={dragged} />
      </Canvas>
    </div>
  )
}

/** Keeps the camera above the centre of the shared 2D view, tilted like a campaign map. */
function CameraRig({ view }: { view: MapView }) {
  const { camera, invalidate } = useThree()
  useEffect(() => {
    const cx = view.x + view.w / 2
    const cz = view.y + view.h / 2
    const distance = view.w * 0.85
    camera.position.set(cx, distance * 0.78, cz + distance * 0.5)
    camera.lookAt(cx, 0, cz)
    camera.updateProjectionMatrix()
    invalidate()
  }, [view, camera, invalidate])
  return null
}

function Provinces(props: Map3DProps & { dragged: RefObject<boolean> }) {
  const { map, units, ownColor, enemyColor, alliedColor } = props
  return (
    <>
      {map.provinces.map((province) => (
        <ProvinceMesh key={province.key} province={province} {...props} />
      ))}
      {map.provinces.map((province) => {
        const counts = units.get(province.key)
        if (!counts) return null
        const columns = [
          counts.friendly > 0 ? { kind: 'friendly', count: counts.friendly, color: ownColor } : null,
          counts.allied ? { kind: 'allied', count: counts.allied, color: alliedColor } : null,
          counts.hostile > 0 ? { kind: 'hostile', count: counts.hostile, color: enemyColor } : null,
        ].filter((c) => c !== null)
        const base = (province.terrain === 'MOUNTAINS' ? MOUNTAIN_HEIGHT : PLAINS_HEIGHT) + 1
        return columns.map((column, index) => {
          const height = 8 + column.count * 6
          const x = province.labelX - (columns.length - 1) * 12 + index * 24
          const selectable = column.kind === 'friendly' && props.onSelectArmy !== undefined
          return (
            <mesh
              key={`${province.key}-${column.kind}`}
              position={[x, base + height / 2, province.labelY + 18]}
              onClick={(event: ThreeEvent<MouseEvent>) => {
                if (!selectable || props.dragged.current) return
                event.stopPropagation()
                props.onSelectArmy!(province.key)
              }}
              onContextMenu={(event: ThreeEvent<MouseEvent>) => {
                event.stopPropagation()
                event.nativeEvent.preventDefault()
                props.onCommand?.(province.key)
              }}
            >
              <cylinderGeometry args={[8, 8, height, 16]} />
              <meshStandardMaterial color={column.color} />
            </mesh>
          )
        })
      })}
    </>
  )
}

function ProvinceMesh({
  province,
  fills,
  selectedKey,
  targets,
  onSelect,
  onCommand,
  onHover,
  dragged,
}: Map3DProps & { province: MapProvince; dragged: RefObject<boolean> }) {
  const [hovered, setHovered] = useState(false)
  const selected = province.key === selectedKey
  const target = targets.get(province.key)
  const height = (province.terrain === 'MOUNTAINS' ? MOUNTAIN_HEIGHT : PLAINS_HEIGHT) + (selected ? SELECTED_LIFT : 0)
  const geometry = useMemo(() => {
    const shapes = parsePolygons(province.svgPath).map((polygon) => {
      // Shape space is x / −y; the mesh is turned flat so −y becomes the map's y (depth goes up).
      const shape = new THREE.Shape()
      polygon.forEach(([x, y], i) => (i === 0 ? shape.moveTo(x, -y) : shape.lineTo(x, -y)))
      return shape
    })
    return new THREE.ExtrudeGeometry(shapes, { depth: height, bevelEnabled: false })
  }, [province.svgPath, height])
  useEffect(() => () => geometry.dispose(), [geometry])
  const emissive = target === 'ATTACK' ? '#5a1410' : target === 'MOVE' ? '#4a3a10' : selected || hovered ? '#2a2a2a' : '#000000'

  return (
    <mesh
      geometry={geometry}
      rotation={[-Math.PI / 2, 0, 0]}
      onClick={(event: ThreeEvent<MouseEvent>) => {
        event.stopPropagation()
        if (!dragged.current) onSelect(province.key)
      }}
      onContextMenu={(event: ThreeEvent<MouseEvent>) => {
        event.stopPropagation()
        event.nativeEvent.preventDefault()
        onCommand?.(province.key)
      }}
      onPointerMove={(event: ThreeEvent<PointerEvent>) => {
        event.stopPropagation()
        onHover(province, event.nativeEvent.clientX, event.nativeEvent.clientY)
      }}
      onPointerOver={(event: ThreeEvent<PointerEvent>) => {
        event.stopPropagation()
        setHovered(true)
      }}
      onPointerOut={() => setHovered(false)}
    >
      <meshStandardMaterial color={fills.get(province.key) ?? '#5b6470'} emissive={emissive} flatShading />
    </mesh>
  )
}
