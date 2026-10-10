package com.forgeflow.gateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** What the edge does with each path - for operators, and for anyone curious. */
@RestController
class RoutesController {

    private final RouteTable routes;

    RoutesController(RouteTable routes) {
        this.routes = routes;
    }

    @GetMapping("/gateway/routes")
    List<RouteTable.Match> routes() {
        return routes.all();
    }
}
