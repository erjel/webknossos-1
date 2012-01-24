importScripts("underscore-min.js", "libs/mjs.js", "core_ext.js", "binary_request.js")

verticesTemplate = null

initializeVerticesTemplate = _.once2 (doneCallback) ->
		
	request
		url : '/binary/model/cube'
		responseType : 'arraybuffer'
		(err, data) ->
			
			callback = doneCallback(err)
			
			unless err
				verticesTemplate = new Int8Array(data)
				callback() if callback
			
			return


self.onmessage = (event) ->
	
	initializeVerticesTemplate (err) ->

		args = event.data
		workerHandle = args.workerHandle
		
		return postMessage({ err, workerHandle }) if err

		vertices = M4x4.moveVertices verticesTemplate, args.position, args.direction
		# vertices = M4x4.transformPointsAffine matrix, verticesTemplate
		
		max_x = min_x = vertices[0]
		max_y = min_y = vertices[1]
		max_z = min_z = vertices[2]
		for i in [3...vertices.length] by 3
			x = vertices[i]
			y = vertices[i + 1]
			z = vertices[i + 2]
			max_x = if x > max_x then x else max_x
			max_y = if y > max_y then y else max_y
			max_z = if z > max_z then z else max_z
			min_x = if x < min_x then x else min_x
			min_y = if y < min_y then y else min_y
			min_z = if z < min_z then z else min_z
		
		minmax = [
			if min_x < 0 then 0 else min_x
			if min_y < 0 then 0 else min_y
			if min_z < 0 then 0 else min_z
			if max_x < 0 then 0 else max_x
			if max_y < 0 then 0 else max_y
			if max_z < 0 then 0 else max_z
		]
		
		postMessage({ vertices, minmax, workerHandle })