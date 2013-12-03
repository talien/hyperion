var hyperion = angular.module('hyperion', []);
     
hyperion.controller('HyperionCtrl', function ($scope, $http, $timeout) {
    
    (function refresh() { 
        $http.get('../rest/counter/counter').success(function(data) {
            $scope.counter = data;
            $timeout(refresh, 1000);
        });
     })();
     
});
