// global data
global_slipperiness = {};
global_boosters = {};
global_jump_pads = {};
global_bouncy_walls = {};
global_min_x = 0; global_max_x = 0;
global_min_y = 0; global_max_y = 0;
global_min_z = 0; global_max_z = 0;
global_has_bounds = false;
global_history = [];
global_file_name = 'boatutils_config';
HISTORY_LIMIT = 20;

SUB_TICKS = 4; 
SUB_STEP_FACTOR = 1.0 / SUB_TICKS;

// commands
__config() -> {
    'commands' -> {
        '' -> 'show_help',
        'bounds <x1> <y1> <z1> <x2> <y2> <z2>' -> 'cmd_bounds',
        'slipperiness <block> <multiplier>' -> 'cmd_slipperiness',
        'slippery <block> <preset>' -> 'cmd_slippery_preset',
        'booster <block> <force>' -> 'cmd_booster',
        'jumppad <block> <force>' -> 'cmd_jumppad',
        'bouncywall <block> <elasticity>' -> 'cmd_bouncywall',
        'undo' -> 'cmd_undo',
        'save' -> 'cmd_save',
        'load' -> 'cmd_load'
    },
    'arguments' -> {
        'block' -> { 'type' -> 'block' },
        'multiplier' -> { 'type' -> 'float' },
        'force' -> { 'type' -> 'float' },
        'elasticity' -> { 'type' -> 'float' },
        'preset' -> { 'type' -> 'term', 'options' -> [ 'ice', 'blue_ice' ] }
    },
    'permissions' -> 0
};

// startup
__on_start() -> cmd_load();

// history
record_history(type, block, old_value) -> (
    put(global_history, null, [type, block, old_value]);
    if(length(global_history) > HISTORY_LIMIT, delete(global_history, 0));
);

// help
show_help() -> (
    p = player();
    print(p, format('bOpenBoatUtils Builder'));
    print(p, format('g/boatutils bounds <x1> <y1> <z1> <x2> <y2> <z2>'));
    print(p, format('g/boatutils slipperiness <block> <multiplier>'));
    print(p, format('g/boatutils slippery <block> <ice|blue_ice>'));
    print(p, format('g/boatutils booster <block> <force>'));
    print(p, format('g/boatutils jumppad <block> <force>'));
    print(p, format('g/boatutils bouncywall <block> <elasticity>'));
    print(p, format('y/boatutils undo'));
    print(p, format('a/boatutils save'));
    print(p, format('a/boatutils load'));
);

// api
set_track_bounds(x1, y1, z1, x2, y2, z2) -> (
    global_min_x = min(x1, x2); global_max_x = max(x1, x2);
    global_min_y = min(y1, y2); global_max_y = max(y1, y2);
    global_min_z = min(z1, z2); global_max_z = max(z1, z2);
    global_has_bounds = true;
);

register_slipperiness(block, multiplier) -> global_slipperiness:block = multiplier;
register_booster(block, force) -> global_boosters:block = force;
register_jump_pad(block, force) -> global_jump_pads:block = force;
register_bouncy_wall(block, elasticity) -> global_bouncy_walls:block = elasticity;

// helpers
inline inside_bounds(x, y, z) -> (
    global_has_bounds && 
    x >= global_min_x && x <= global_max_x && 
    y >= global_min_y && y <= global_max_y && 
    z >= global_min_z && z <= global_max_z
);

// main loop
__on_tick() -> (
    if(!global_has_bounds, return());
    boats = entity_list('boat');
    if(!boats, return());
    
    for(boats, boat = _;
        pos = pos(boat);
        bx = pos:0; by = pos:1; bz = pos:2;
        if(!inside_bounds(bx, by, bz), continue());
        
        motion = query(boat, 'motion');
        mx = motion:0; my = motion:1; mz = motion:2;
        
        // sub ticks loop
        loop(SUB_TICKS,
            speed_sq = mx * mx + mz * mz;
            if(speed_sq < 0.0000001, continue());
            
            yaw = query(boat, 'yaw');
            rad = radians(yaw);
            sin_rad = sin(rad);
            cos_rad = cos(rad);
            
            current_block = str(block(bx, by, bz));
            under_block = str(block(bx, by - 0.1, bz));
            
            speed = sqrt(speed_sq);
            inv_speed = 1.0 / speed;
            nx = mx * inv_speed * 0.8;
            nz = mz * inv_speed * 0.8;
            
            front_block = str(block(bx + nx, by + 0.2, bz + nz));
            front_upper_block = str(block(bx + nx, by + 1.2, bz + nz));
            
            // step up blocks
            if(front_block != 'air' && front_block != 'water' && front_block != 'lava',
                if(front_upper_block == 'air' || front_upper_block == 'water',
                    bx = bx + (mx * inv_speed * 0.3 * SUB_STEP_FACTOR);
                    by = by + (1.05 * SUB_STEP_FACTOR);
                    bz = bz + (mz * inv_speed * 0.3 * SUB_STEP_FACTOR);
                    modify(boat, 'pos', [bx, by, bz]);
                    my = max(my, 0.07);
                );
            );
            
            // air stepping
            if(current_block == 'air' && under_block != 'air' && under_block != 'water',
                mx = mx - sin_rad * 0.05 * SUB_STEP_FACTOR;
                mz = mz + cos_rad * 0.05 * SUB_STEP_FACTOR;
            );
            
            // slippery multiplier scaled exponentially for sub-ticks
            if((mult = global_slipperiness:under_block) != null,
                mx = mx * (mult ^ SUB_STEP_FACTOR);
                mz = mz * (mult ^ SUB_STEP_FACTOR);
            );
            
            // booster force divided equally across sub-steps
            if((force = global_boosters:under_block) != null,
                mx = mx - sin_rad * force * SUB_STEP_FACTOR;
                mz = mz + cos_rad * force * SUB_STEP_FACTOR;
            );
            
            // jump pad
            if((upforce = global_jump_pads:under_block) != null,
                my = upforce;
            );
            
            // bouncy wall vectors
            wall_offset_x = if(mx >= 0, 0.8, -0.8);
            wall_offset_z = if(mz >= 0, 0.8, -0.8);
            wall_x = str(block(bx + wall_offset_x, by + 0.1, bz));
            wall_z = str(block(bx, by + 0.1, bz + wall_offset_z));
            wall_diag = str(block(bx + (wall_offset_x * 0.7), by + 0.1, bz + (wall_offset_z * 0.7)));
            
            // X reflection
            if((elasticity = global_bouncy_walls:wall_x) == null, elasticity = global_bouncy_walls:wall_diag);
            if(elasticity != null, mx = -mx * elasticity);
            
            // Z reflection
            if((elasticity = global_bouncy_walls:wall_z) == null, elasticity = global_bouncy_walls:wall_diag);
            if(elasticity != null, mz = -mz * elasticity);
            
            // Clamp small values
            if(abs(mx) < 0.00001, mx = 0);
            if(abs(mz) < 0.00001, mz = 0);
            if(abs(my) < 0.00001, my = 0);
            
            // i move the boat position so the next loop cycle detects blocks accurately
            bx = bx + (mx * SUB_STEP_FACTOR);
            by = by + (my * SUB_STEP_FACTOR);
            bz = bz + (mz * SUB_STEP_FACTOR);
            modify(boat, 'pos', [bx, by, bz]);
        );
        
        // send back final movement vector to the vanilla entity
        modify(boat, 'motion', [mx, my, mz]);
    );
);

// commands
cmd_bounds(x1, y1, z1, x2, y2, z2) -> (
    record_history('bounds', null, [global_min_x, global_min_y, global_min_z, global_max_x, global_max_y, global_max_z, global_has_bounds]);
    set_track_bounds(x1, y1, z1, x2, y2, z2);
    print(player(), format('aTrack bounds updated.'));
);

cmd_slipperiness(block, multiplier) -> (
    record_history('slipperiness', block, global_slipperiness:block);
    register_slipperiness(block, multiplier);
    print(player(), format('a' + block + ' slipperiness = ' + multiplier));
);

cmd_slippery_preset(block, preset) -> (
    multiplier = if(preset == 'blue_ice', 0.989, 0.98);
    record_history('slipperiness', block, global_slipperiness:block);
    register_slipperiness(block, multiplier);
    print(player(), format('a' + block + ' now uses preset "' + preset + '".'));
);

cmd_booster(block, force) -> (
    record_history('booster', block, global_boosters:block);
    register_booster(block, force);
    print(player(), format('aBooster set for ' + block));
);

cmd_jumppad(block, force) -> (
    record_history('jumppad', block, global_jump_pads:block);
    register_jump_pad(block, force);
    print(player(), format('aJump pad set for ' + block));
);

cmd_bouncywall(block, elasticity) -> (
    record_history('bouncywall', block, global_bouncy_walls:block);
    register_bouncy_wall(block, elasticity);
    print(player(), format('aBouncy wall set for ' + block));
);

// undo
cmd_undo() -> (
    if(!global_history, print(player(), format('cNothing to undo.')); return());
    action = delete(global_history, -1);
    type = action:0; block = action:1; old = action:2;
    p = player();
    
    if(type == 'bounds', (
        global_min_x = old:0; global_min_y = old:1; global_min_z = old:2;
        global_max_x = old:3; global_max_y = old:4; global_max_z = old:5;
        global_has_bounds = old:6;
        print(p, format('eBounds restored.'));
    ), type == 'slipperiness', (
        if(old == null, delete(global_slipperiness, block), global_slipperiness:block = old);
        print(p, format('eUndid slipperiness.'));
    ), type == 'booster', (
        if(old == null, delete(global_boosters, block), global_boosters:block = old);
        print(p, format('eUndid booster.'));
    ), type == 'jumppad', (
        if(old == null, delete(global_jump_pads, block), global_jump_pads:block = old);
        print(p, format('eUndid jump pad.'));
    ), type == 'bouncywall', (
        if(old == null, delete(global_bouncy_walls, block), global_bouncy_walls:block = old);
        print(p, format('eUndid bouncy wall.'));
    ));
);

// save/load
cmd_save() -> (
    package = {
        'bounds' -> [global_min_x, global_min_y, global_min_z, global_max_x, global_max_y, global_max_z, global_has_bounds],
        'slipperiness' -> global_slipperiness,
        'boosters' -> global_boosters,
        'jump_pads' -> global_jump_pads,
        'bouncy_walls' -> global_bouncy_walls
    };
    write_file(global_file_name, 'json', package);
    print(player(), format('aConfiguration saved successfully.'));
);

cmd_load() -> (
    package = read_file(global_file_name, 'json');
    if(package == null, 
        logger('info', 'OpenBoatUtils: no save file found.');
        return();
    );
    global_min_x = package:'bounds':0;
    global_min_y = package:'bounds':1;
    global_min_z = package:'bounds':2;
    global_max_x = package:'bounds':3;
    global_max_y = package:'bounds':4;
    global_max_z = package:'bounds':5;
    global_has_bounds = package:'bounds':6;
    global_slipperiness = package:'slipperiness';
    global_boosters = package:'boosters';
    global_jump_pads = package:'jump_pads';
    global_bouncy_walls = package:'bouncy_walls';
    logger('info', 'OpenBoatUtils: configuration loaded.');
);
