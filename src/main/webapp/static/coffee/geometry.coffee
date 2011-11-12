class Geometry
  constructor: ->
    @polyhedral = []
  
  load: (data) ->
    
    vertices = {}
    edges = {}
    faces = []
    
    get_edge = (vertex1, vertex2) ->
      if Utils.arrayCompare(vertex1.toArray(), vertex2.toArray()) == 1
        [vertex1, vertex2] = [vertex2, vertex1]
      
      hit_edges = edges["#{vertex1}x#{vertex2}"] ?= []
      
      for edge in hit_edges
        return edge if edge.adjoining_faces.length < 2
      
      hit_edges.push tmp = new Edge(vertex1, vertex2)
      tmp
    
    for polygon in data
      for face in @triangulate(polygon)
        
        face_vertices = for _vertex in face
          vertices[_vertex.toString()] ?= new Vertex(_vertex)
        
        face_edges = for i in [0...face_vertices.length]
          
          vertex1 = face_vertices[i]
          vertex2 = face_vertices[(i + 1) % face_vertices.length]
          
          get_edge(vertex1, vertex2)
        
        faces.push tmp = new Face(face_vertices, face_edges)
        tmp
    
    @polyhedral.push new Polyhedron(
      faces,
      Object.keys(edges).map((a) -> edges[a]).reduce((r,a) -> r.concat a),
      Object.keys(vertices).map((a) -> vertices[a])
    )
  
  triangulate: (polygon) ->
    
    output = []
      
    # monotonize
    polygon.sort((a, b) -> b.y - a.y)
    monotones = [polygon]
    
    # triangulate each monotone polygon
    for p in monotones
      
      stack = p[0..1]
      for u in p[2..-1]
        first = stack[0]
        last = stack[stack.length - 1]
        has_first = u.adjacent1 == first or u.adjacent2 == first
        has_last = u.adjacent1 == last or u.adjacent2 == last
        
        if has_first and has_last
          for i in [1...(stack.length - 1)]
            output.push [u, stack[i], 0]
          return output
          
        else if has_first
          for i in [1...stack.length]
            output.push [u, stack[i], 1]
          stack = [last, u]
          
        else if has_last
          prelast = stack[stack.length - 2]
          
          while stack.length > 1 and Math.vecAngleIsReflex(u.sub(last), prelast.sub(last)) > 0
            output.push [u, prelast, 2]
            stack.pop()
            [prelast, last] = stack[-2..-1]
            
          stack.push u
    
    output
  
  overlaps = (ex1, ex2) ->
    
    ex1.min[0] < ex2.max[0] and
    ex1.max[0] > ex2.min[0] and
    ex1.min[1] < ex2.max[1] and
    ex1.max[1] > ex2.min[1] and
    ex1.min[2] < ex2.max[2] and
    ex1.max[2] > ex2.min[2]
  
  calc_extent = (vertex, max, min) ->
    
    unless max? or min?
      [vertex.toArray(), vertex.toArray()]
    else
      [
        [Math.max(vertex.x, max[0]), Math.max(vertex.y, max[1]), Math.max(vertex.z, max[2])]
        [Math.min(vertex.x, min[0]), Math.min(vertex.y, min[1]), Math.min(vertex.z, min[2])]
      ]
  
  
  
  class Polyhedron
    constructor: (@faces, @edges, @vertices) ->
    
      face.polyhedron = @ for face in @faces
      
      for edge in @edges
        edge.calc_interior()
        edge.polyhedron = @
    
      # calc extent
      for vertex in @vertices
        [max, min] = calc_extent(vertex, max, min)
        vertex.calc_interior()
        vertex.polyhedron = @
        
      @extent =
        max: max
        min: min
      
      @links = []
      
      
  class Face
    constructor: (@vertices, @edges, @plane) ->
      
      for vertex in @vertices
        [max, min] = calc_extent(vertex, max, min)
        
      @extent =
        max: max
        min: min
        
      # calc plane equation
      unless @plane?
        [v1, v2, v3] = @vertices
        
        vec1 = v2.sub(v1)
        vec2 = v2.sub(v3)
        
        plane = Math.normalizeVector(Math.crossProduct(vec1, vec2))
        
        plane.push(plane[0] * v1.x + plane[1] * v1.y + plane[2] * v1.z)
        @plane = plane
      
      edge.adjoining_faces.push(@) for edge in @edges
  
  class Edge
    constructor: (vertex1, vertex2) ->
      
      @vertices = [vertex1, vertex2]
      @adjoining_faces = []
      
      vertex1.edges.push @
      vertex2.edges.push @
      
      vertex1.adjacents.push vertex2
      
      @interior = true
      
      @links = []
    
    calc_interior: ->
      @interior = Utils.arrayEquals(@adjoining_faces[0].plane, @adjoining_faces[1].plane)
      
  
  class Vertex
    constructor: (_vertex = [0,0,0]) ->
      @x = _vertex[0]
      @y = _vertex[1]
      @z = _vertex[2]
      
      @edges = []
      
      # null = unknown, -1 = outside, 0 = boundary, 1 = inside
      @status = null
      
      @adjacents = []
      
      @interior = true
    
    calc_interior: ->
      for edge in @edges
        return edge.interior = false unless edge.interior
      
    
    sub: (v2) ->
      [@x - v2.x, @y - v2.y, @z - v2.z]
      
    toArray: ->
      [@x, @y, @z]
    
    toString: ->
      @toArray().toString()
    
    equals: (a) ->
      @x == a.x and @y == a.y and @z == a.z
  
  
  
  
  
  
  split: (p1, p2) ->
    if overlaps(p1.extent, p2.extent)
      for face1 in p1.faces
        if overlaps(face1.extent, p2.extent)
          for face2 in p2.faces
            if overlaps(face1.extent, face2.extent)
              @find_intersections(face1, face2)
  
  find_intersections: (face1, face2) ->
      
    distance_vertex_to_plane = (vertex, plane) ->
      if plane[3] < 0
        (vertex.x * (-plane[0]) + vertex.y * (-plane[1]) + vertex.z * (-plane[2])) + plane[3]
      else
        (vertex.x * plane[0] + vertex.y * plane[1] + vertex.z * plane[2]) - plane[3]
      
    distance_vertices_to_plane = (vertices, plane) ->
      for vertex in vertices
        s = distance_vertex_to_plane(vertex, plane)
        if max? and min?
          max = Math.max(max, s)
          min = Math.min(min, s)
        else
          max = min = s
      [max, min]
    
    line_segment = (_face1, _face2) ->
      points = []
      
      for v in _face1.vertices
        points.push v if distance_vertex_to_plane(v, _face2.plane) == 0
      
      return points if points.length == 2
      
      for e in _face1.edges
        v1 = e.vertices[0]
        v2 = e.vertices[1]
        d1 = distance_vertex_to_plane(v1, _face2.plane)
        d2 = distance_vertex_to_plane(v2, _face2.plane)
        
        if (d1 < 0 < d2) or (d1 > 0 > d2)
          d1 = Math.abs(d1)
          d2 = Math.abs(d2)
          vec = v2.sub(v1)
          quotient = (d1 / (d1 + d2))
          
          vertex = new Vertex [
            v1.x + quotient * vec[0]
            v1.y + quotient * vec[1]
            v1.z + quotient * vec[2]
          ]
          vertex.polyhedron = _face1.polyhedron
          vertex.interior = false unless e.interior
          vertex.linked_edge = e
          points.push vertex
      
      return points
      
    line_segment_intersection = (seg1, seg2) ->
      
      p = seg1[0]
      
      d1 = 0
      d2 = Math.vecLength(seg1[1].sub(p))
      d3 = Math.vecLength(seg2[0].sub(p))
      d4 = Math.vecLength(seg2[1].sub(p))
      
      if d1 > d2
        [d1, d2] = [d2, d1]
      if d3 > d4
        [d3, d4] = [d4, d3]
      
      if d3 > d2 # both segments don't intersect
        return [] 
      if d2 == d3 # they only touch in one point
        return [seg1[1]]
        
      [
        if d3 <= d1
          seg1[0]
        else # d1 < d3 < d2
          seg2[0]
       ,
        if d4 <= d2
          seg2[1]
        else # d3 < d2 < d4
          seg1[1]
      ]
      
      
    
    [max, min] = distance_vertices_to_plane(face1.vertices, face2.plane)
    
    if (max >= 0 and min >= 0) or (max <= 0 and min <= 0)
      return false # coplanar or no intersection at all
    else
      [max, min] = distance_vertices_to_plane(face2.vertices, face1.plane)
      
      if (max >= 0 and min >= 0) or (max <= 0 and min <= 0)
        return false # still no intersection
      else
        
        line_segment_intersection(
          line_segment(face1, face2),
          line_segment(face2, face1)
        )
        