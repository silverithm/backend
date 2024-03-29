package com.silverithm.vehicleplacementsystem.service;

import com.silverithm.vehicleplacementsystem.dto.AddEmployeeRequest;
import com.silverithm.vehicleplacementsystem.dto.ElderUpdateRequestDTO;
import com.silverithm.vehicleplacementsystem.dto.ElderlyDTO;
import com.silverithm.vehicleplacementsystem.dto.EmployeeDTO;
import com.silverithm.vehicleplacementsystem.dto.EmployeeUpdateRequestDTO;
import com.silverithm.vehicleplacementsystem.dto.Location;
import com.silverithm.vehicleplacementsystem.entity.AppUser;
import com.silverithm.vehicleplacementsystem.entity.Employee;
import com.silverithm.vehicleplacementsystem.repository.EmployeeRepository;
import com.silverithm.vehicleplacementsystem.repository.UserRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeService {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;


    @Autowired
    private GeocodingService geocodingService;

    public void addEmployee(Long userId, AddEmployeeRequest addEmployeeRequest) {

        Location homeAddress = geocodingService.getAddressCoordinates(addEmployeeRequest.homeAddress());
        Location workPlace = geocodingService.getAddressCoordinates(addEmployeeRequest.workPlace());

        System.out.println(homeAddress + " " + addEmployeeRequest.homeAddress());
        System.out.println(workPlace + " " + addEmployeeRequest.workPlace());

        AppUser user = userRepository.findById(userId).orElseThrow();

        Employee employee = new Employee(addEmployeeRequest.name(), workPlace, homeAddress,
                addEmployeeRequest.maxCapacity(), user);
        employeeRepository.save(employee);
    }

    public List<EmployeeDTO> getEmployees(Long userId) {
        List<Employee> employees = employeeRepository.findByUserId(userId);

        List<EmployeeDTO> employeeDTOS = employees.stream()
                .map(employee -> new EmployeeDTO(employee.getId(), employee.getName(), employee.getHomeAddress(),
                        employee.getWorkPlace(),
                        employee.getMaximumCapacity())).collect(Collectors.toList());

        return employeeDTOS;

    }

    public void deleteEmployee(Long id) {
        employeeRepository.deleteById(id);
    }

    @Transactional
    public void updateEmployee(Long id, EmployeeUpdateRequestDTO employeeUpdateRequestDTO) {

        Location updatedHomeAddress = geocodingService.getAddressCoordinates(employeeUpdateRequestDTO.homeAddress());
        Location updatedWorkPlace = geocodingService.getAddressCoordinates(employeeUpdateRequestDTO.workPlace());

        Employee employee = employeeRepository.findById(id).orElseThrow();
        employee.update(employeeUpdateRequestDTO.name(), updatedHomeAddress,
                updatedWorkPlace, employeeUpdateRequestDTO.maxCapacity());
    }

}
