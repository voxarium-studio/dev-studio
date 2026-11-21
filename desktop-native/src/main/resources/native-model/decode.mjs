import { buildMesh, parseMesh } from './MeshPreview.mjs'
import { buildFbxMesh } from './FbxPreview.mjs'
import { buildVoxelMesh, parseVoxelFile } from './VoxPreview.mjs'
import { once } from 'node:events'

const extension = process.argv[2]

// The desktop sends scoped Explorer bytes. The reader sees neither a project path nor a Studio credential.
async function decode() {
  const chunks = []
  let count = 0
  for await (const chunk of process.stdin) {
    count += chunk.length
    if (count > 64 * 1024 * 1024) throw new Error('Model exceeds the preview limit.')
    chunks.push(chunk)
  }
  const bytes = Buffer.concat(chunks)
  const data = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
  if (extension === 'vox' || extension === 'voxarium') {
    const voxels = parseVoxelFile(data)
    const model = buildVoxelMesh(voxels)
    model.objects = model.ranges.map(({ first, count, index }) => ({
      first, count, index, name: voxels.placements[index].name, hidden: !!voxels.placements[index].hidden,
    }))
    return model
  }
  if (extension === 'fbx') return await buildFbxMesh(data)
  if (['glb', 'gltf', 'stl', 'obj'].includes(extension)) return buildMesh(parseMesh(`model.${extension}`, data))
  throw new Error('Unsupported model format.')
}

async function emit(buffer) {
  if (!process.stdout.write(buffer)) await once(process.stdout, 'drain')
}

async function header(data) {
  const metadata = Buffer.from(JSON.stringify(data), 'utf8')
  if (metadata.length > 1024 * 1024) throw new Error('Model metadata exceeds the preview limit.')
  const length = Buffer.alloc(4)
  length.writeUInt32LE(metadata.length)
  await emit(length)
  await emit(metadata)
}

let model
try {
  model = await decode()
} catch (error) {
  await header({ error: String(error?.message ?? error) })
}

if (model) {
  const textures = model.textures ?? []
  const metadata = {
    triangles: model.stats.triangles,
    truncated: model.stats.truncated,
    warnings: model.stats.warnings,
    objects: model.objects,
    groups: model.groups,
    colors: !!model.colors,
    uvs: !!model.uvs,
    textures: textures.map(({ id, name, bytes, wrapS, wrapT }) => ({ id, name, length: bytes.byteLength, wrapS, wrapT })),
  }
  await header(metadata)
  const arrays = [model.positions, model.normals, model.colors, model.uvs, ...textures.map(({ bytes }) => bytes)]
  for (const array of arrays) {
    if (array) await emit(Buffer.from(array.buffer, array.byteOffset, array.byteLength))
  }
}
